package dev.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small-workspace hybrid search. Credentials and cached vectors never cross workspaces. */
@Service
public class SemanticSearch {
    public record View(boolean custom, boolean keyConfigured, String baseUrl, String model, List<String> allowedHosts, double minSimilarity) {}
    public record Candidate(String id, String title, String content) {}
    public record Hit(String id, double score) {}
    public record Result(List<Hit> hits, String mode, String warning) {}
    private final ModelSettings settings;
    private final JsonHttp http;
    private final Map<String, float[]> cache = Collections.synchronizedMap(new LinkedHashMap<>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, float[]> entry) { return size() > 512; }
    });
    public SemanticSearch(@Value("${recall.data-dir}") String dir, ObjectMapper mapper, Environment env, JsonHttp http) throws IOException {
        settings = new ModelSettings(Path.of(dir, "embeddings").toString(), mapper, env);
        this.http = http;
    }
    public View view(String workspace) throws IOException {
        var v = settings.view(workspace);
        return new View(v.custom(), v.custom(), v.custom() ? v.baseUrl() : "", v.custom() ? v.model() : "", v.allowedHosts(), settings.minSimilarity(workspace));
    }
    public View save(String workspace, String url, String model, String key) throws IOException { return save(workspace, url, model, key, .55); }
    public View save(String workspace, String url, String model, String key, double minSimilarity) throws IOException {
        if (!Double.isFinite(minSimilarity) || minSimilarity < 0 || minSimilarity > 1) throw new IllegalArgumentException("相似度阈值必须在 0 到 1 之间。");
        var candidate = settings.candidate(workspace, url, model, key);
        embed(candidate, List.of("连接检查")); // Verify before replacing a working configuration.
        settings.save(workspace, url, model, key, minSimilarity);
        invalidate(workspace);
        return view(workspace);
    }
    public View delete(String workspace) throws IOException { settings.delete(workspace); invalidate(workspace); return view(workspace); }
    public void invalidate(String workspace) { synchronized (cache) { cache.keySet().removeIf(k -> k.startsWith(workspace + ":")); } }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("摘要计算失败"); }
    }
    List<float[]> embed(ModelSettings.Effective config, List<String> texts) {
        var data = http.post(config.baseUrl() + "/embeddings", config.apiKey(), Map.of("model", config.model(), "input", texts, "encoding_format", "float")).path("data");
        if (!data.isArray() || data.size() != texts.size()) throw new IllegalStateException("向量接口返回数量不一致。");
        List<float[]> result = new ArrayList<>(Collections.nCopies(texts.size(), null));
        int dimensions = -1;
        for (var row : data) {
            int i = row.path("index").asInt(-1); var vector = row.path("embedding");
            if (i < 0 || i >= texts.size() || result.get(i) != null || !vector.isArray() || vector.size() < 2 || vector.size() > 4096)
                throw new IllegalStateException("向量接口格式异常或维度超出 2–4096。");
            if (dimensions != -1 && vector.size() != dimensions) throw new IllegalStateException("向量维度不一致。");
            dimensions = vector.size(); float[] values = new float[dimensions]; double norm = 0;
            for (int j = 0; j < dimensions; j++) {
                if (!vector.get(j).isNumber()) throw new IllegalStateException("向量包含非数字。");
                values[j] = (float) vector.get(j).asDouble(); norm += values[j] * (double) values[j];
            }
            if (!Double.isFinite(norm) || norm <= 0) throw new IllegalStateException("向量数值无效。");
            result.set(i, values);
        }
        return result;
    }
    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) throw new IllegalStateException("向量维度改变，请重新保存向量配置。");
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * (double)b[i]; aa += a[i] * (double)a[i]; bb += b[i] * (double)b[i]; }
        return dot / Math.sqrt(aa * bb);
    }
    public Result search(String workspace, String query, List<Candidate> input) throws IOException {
        // Bound initial indexing cost. Prioritize keyword matches; preserve the order of ties.
        var candidates = input.stream().sorted(Comparator.comparingDouble((Candidate c) -> Retrieval.score(query, c.title()+" "+c.content())).reversed()).limit(128).toList();
        var lexical = bm25(query, candidates);
        String warning = input.size() > 128 ? "本轮仅处理前 128 个候选片段，请缩小资料范围。" : "";
        if (candidates.isEmpty() || !view(workspace).custom()) return new Result(lexical, "关键词 BM25", warning);
        try {
            var config = settings.effective(workspace);
            double minSimilarity = settings.minSimilarity(workspace);
            String prefix = workspace + ":" + digest(config.baseUrl()+"\n"+config.model()+"\n"+config.apiKey()) + ":";
            List<String> texts = candidates.stream().map(c -> c.title()+"\n"+c.content()).toList();
            List<float[]> vectors = new ArrayList<>(Collections.nCopies(texts.size(), null));
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                vectors.set(i, cache.get(prefix + digest(texts.get(i))));
                if (vectors.get(i) == null) missing.add(i);
            }
            for (int start = 0; start < missing.size(); start += 32) {
                var indexes = missing.subList(start, Math.min(start + 32, missing.size()));
                var batch = embed(config, indexes.stream().map(texts::get).toList());
                for (int j = 0; j < indexes.size(); j++) {
                    int i = indexes.get(j); vectors.set(i, batch.get(j)); cache.put(prefix + digest(texts.get(i)), batch.get(j));
                }
            }
            var q = embed(config, List.of(query)).getFirst();
            List<Hit> dense = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                double score = cosine(q, vectors.get(i));
                if (score >= minSimilarity) dense.add(new Hit(candidates.get(i).id(), score));
            }
            dense.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::id));
            Map<String, Double> fused = new HashMap<>();
            for (var ranking : List.of(lexical, dense)) for (int i = 0; i < Math.min(30, ranking.size()); i++) fused.merge(ranking.get(i).id(), 1.0/(60+i+1), Double::sum);
            return new Result(fused.entrySet().stream().map(e -> new Hit(e.getKey(), e.getValue())).sorted(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::id)).toList(), "BM25 + 向量 · RRF", warning);
        } catch (IllegalStateException e) {
            return new Result(lexical, "关键词 BM25（向量降级）", warning + " 向量服务不可用或返回无效，已回退关键词检索；可在设置中重新检查。");
        }
    }
    static List<Hit> bm25(String query, List<Candidate> candidates) {
        var q = Retrieval.tokens(query); var tokens = candidates.stream().map(c -> Retrieval.terms(c.title()+" "+c.content())).toList();
        double avg = tokens.stream().mapToInt(List::size).average().orElse(1); List<Hit> result = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            double score = 0;
            for (String term : q) if (tokens.get(i).contains(term)) {
                long df = tokens.stream().filter(t -> t.contains(term)).count();
                long tf = tokens.get(i).stream().filter(term::equals).count();
                score += Math.log(1+(candidates.size()-df+.5)/(df+.5))*tf*2.2/(tf+1.2*(.25+.75*tokens.get(i).size()/Math.max(1, avg)));
            }
            if (score > 0) result.add(new Hit(candidates.get(i).id(), score));
        }
        result.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::id)); return result;
    }
}
