package dev.recall;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class Retrieval {
    public record Source(String id, String title, String content, String provider, double score) {}
    public record Provider(String id, String name, boolean ready, String description) {}
    private final Environment env;
    private final JsonHttp http;
    public Retrieval(Environment env, JsonHttp http) { this.env = env; this.http = http; }
    private String setting(String name) { return env.getProperty("recall." + name, ""); }
    public List<Provider> providers() {
        return List.of(new Provider("none", "不使用知识库", true, "只使用对话和长期记忆"),
            new Provider("local", "Local · 本地知识库", true, "中英文关键词检索，无需额外服务"),
            new Provider("dify", "Dify", ready("dify"), "接入已有 Dify 知识库"),
            new Provider("ragflow", "RAGFlow", ready("ragflow"), "接入已有 RAGFlow 数据集"));
    }
    private boolean ready(String id) { return !setting(id + "-key").isBlank() && !setting(id + "-dataset").isBlank() && !setting(id + "-url").isBlank(); }
    public List<Source> retrieve(String provider, String query, List<Store.Item> documents) {
        if (provider.equals("none")) return List.of();
        if (provider.equals("local")) return local(query, documents);
        if (!Set.of("dify", "ragflow").contains(provider)) throw new IllegalArgumentException("不支持的 RAG 服务。");
        if (!ready(provider)) throw new IllegalArgumentException("请先在服务端配置 " + provider + " 的地址、密钥和知识库 ID。");
        String base = setting(provider + "-url").replaceAll("/+$", "");
        var body = provider.equals("dify")
            ? Map.of("query", query, "retrieval_model", Map.of("search_method", "semantic_search", "reranking_enable", false, "top_k", 5, "score_threshold_enabled", false))
            : Map.of("question", query, "dataset_ids", List.of(setting("ragflow-dataset")), "page_size", 5);
        String url = provider.equals("dify") ? base + "/datasets/" + setting("dify-dataset") + "/retrieve" : base + "/api/v1/retrieval";
        var result = http.post(url, setting(provider + "-key"), body);
        if (provider.equals("ragflow") && result.path("code").asInt(-1) != 0) throw new IllegalStateException("RAGFlow 检索失败，请检查知识库配置。");
        var nodes = provider.equals("dify") ? result.path("records") : result.path("data").path("chunks");
        if (!nodes.isArray()) throw new IllegalStateException("知识库返回格式异常。");
        List<Source> sources = new ArrayList<>();
        for (var node : nodes) {
            if (sources.size() == 5) break;
            var segment = provider.equals("dify") ? node.path("segment") : node;
            String content = segment.path("content").asText("");
            if (content.isBlank()) continue;
            String title = provider.equals("dify") ? segment.path("document").path("name").asText("Dify 文档") : node.path("document_keyword").asText("RAGFlow 文档");
            sources.add(new Source("K" + (sources.size() + 1), title, clip(content, 2500), provider, node.path(provider.equals("dify") ? "score" : "similarity").asDouble()));
        }
        return sources;
    }
    public static String clip(String text, int size) { return text.length() > size ? text.substring(0, size) : text; }
    static Set<String> tokens(String text) {
        return new HashSet<>(terms(text));
    }
    static List<String> terms(String text) {
        List<String> tokens = new ArrayList<>();
        var matcher = Pattern.compile("[a-z0-9_]+|[\\p{IsHan}]+", Pattern.CASE_INSENSITIVE).matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (word.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                if (word.length() == 1) tokens.add(word);
                for (int i = 0; i < word.length() - 1; i++) tokens.add(word.substring(i, i + 2));
            } else tokens.add(word);
        }
        return tokens;
    }
    public static double score(String query, String content) {
        Set<String> q = tokens(query), text = tokens(content);
        if (q.isEmpty()) return 0;
        return q.stream().filter(text::contains).count() / (double) q.size();
    }
    public static List<Source> local(String query, List<Store.Item> documents) {
        List<Source> candidates = new ArrayList<>();
        for (var doc : documents) {
            for (int start = 0; start < doc.content().length(); start += 700) {
                String chunk = doc.content().substring(start, Math.min(start + 900, doc.content().length()));
                double score = score(query, doc.title() + " " + chunk);
                if (score > 0) candidates.add(new Source(doc.id(), doc.title(), chunk, "local", score));
            }
        }
        var ranked = candidates.stream().sorted(Comparator.comparingDouble(Source::score).reversed()).limit(5).toList();
        List<Source> result = new ArrayList<>();
        for (var s : ranked) result.add(new Source("K" + (result.size() + 1), s.title(), s.content(), s.provider(), s.score()));
        return result;
    }
}
