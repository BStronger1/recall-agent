package dev.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;

/** Per-workspace encrypted model credentials. Public views never contain a key. */
@Service
public class ModelSettings {
    public record View(boolean custom, boolean keyConfigured, String baseUrl, String model, List<String> allowedHosts) {}
    public record Effective(String baseUrl, String model, String apiKey) {
        @Override public String toString() { return "Effective[credentials redacted]"; }
    }
    record Stored(String baseUrl, String model, String encryptedKey) {}
    private final Path root;
    private final ObjectMapper mapper;
    private final Environment env;
    private final byte[] masterKey;
    private final Set<String> hosts;
    private static final SecureRandom RANDOM = new SecureRandom();

    public ModelSettings(@Value("${recall.data-dir}") String dir, ObjectMapper mapper, Environment env) throws IOException {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.mapper = mapper; this.env = env;
        Files.createDirectories(root);
        Path keyFile = root.resolve(".model-encryption-key");
        if (!Files.exists(keyFile)) {
            byte[] generated = new byte[32]; RANDOM.nextBytes(generated);
            try { Files.write(keyFile, generated, StandardOpenOption.CREATE_NEW); }
            catch (FileAlreadyExistsException ignored) { }
        }
        privateFile(keyFile);
        masterKey = Files.readAllBytes(keyFile);
        if (masterKey.length != 32) throw new IOException("Invalid model encryption key; restore the data directory backup.");
        hosts = new TreeSet<>();
        for (String host : env.getProperty("recall.allowed-model-hosts", "api.openai.com,api.deepseek.com,dashscope.aliyuncs.com,dashscope-intl.aliyuncs.com,api.siliconflow.cn,www.dmxapi.cn,api.moonshot.cn,open.bigmodel.cn").split(",")) {
            if (!host.isBlank()) hosts.add(host.strip().toLowerCase(Locale.ROOT));
        }
    }
    private static void privateFile(Path file) throws IOException {
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
    private Path path(String workspace) {
        if (workspace == null || !workspace.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("工作区凭据无效。");
        return root.resolve(workspace + ".model.json");
    }
    private Stored stored(String workspace) throws IOException {
        Path file = path(workspace);
        return Files.exists(file) ? mapper.readValue(file.toFile(), Stored.class) : null;
    }
    public synchronized View view(String workspace) throws IOException {
        Stored stored = stored(workspace);
        return stored == null
            ? new View(false, !env.getProperty("recall.model-key", "").isBlank(), env.getProperty("recall.model-url", ""), env.getProperty("recall.model", ""), List.copyOf(hosts))
            : new View(true, true, stored.baseUrl(), stored.model(), List.copyOf(hosts));
    }
    public synchronized Effective effective(String workspace) throws IOException {
        Stored stored = stored(workspace);
        if (stored == null) return new Effective(env.getProperty("recall.model-url", ""), env.getProperty("recall.model", ""), env.getProperty("recall.model-key", ""));
        // Revalidate if the administrator has removed a host since this was saved.
        return new Effective(validateUrl(stored.baseUrl()), stored.model(), decrypt(workspace, stored.encryptedKey()));
    }
    String validateUrl(String value) {
        try {
            URI url = URI.create(value.strip());
            String host = url.getHost();
            if (!"https".equalsIgnoreCase(url.getScheme()) || host == null || !hosts.contains(host.toLowerCase(Locale.ROOT))
                || url.getRawUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)
                || url.getRawQuery() != null || url.getRawFragment() != null || url.getRawPath().contains("%")
                || url.getRawPath().contains("..") || url.getRawPath().contains("//")) throw new IllegalArgumentException();
            return new URI("https", null, host.toLowerCase(Locale.ROOT), -1, url.getPath().replaceAll("/+$", ""), null, null).toString();
        } catch (Exception e) { throw new IllegalArgumentException("API 地址必须为站点允许域名的 HTTPS 地址，不能包含账号、查询参数或自定义端口。"); }
    }
    public synchronized Effective candidate(String workspace, String baseUrl, String model, String apiKey) throws IOException {
        String url = validateUrl(baseUrl);
        Stored prior = stored(workspace);
        String key = apiKey == null ? "" : apiKey.strip();
        if (key.isBlank()) {
            if (prior == null || !prior.baseUrl().equals(url)) throw new IllegalArgumentException("请填写 API 密钥；更换接口地址时必须重新填写密钥。");
            key = decrypt(workspace, prior.encryptedKey());
        }
        if (key.length() < 8 || key.length() > 4096 || key.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException("API 密钥格式不正确。");
        if (model == null || model.isBlank() || model.length() > 160 || model.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("请填写有效模型名。");
        return new Effective(url, model.strip(), key);
    }
    public synchronized View save(String workspace, String baseUrl, String model, String apiKey) throws IOException {
        Effective candidate = candidate(workspace, baseUrl, model, apiKey);
        var stored = new Stored(candidate.baseUrl(), candidate.model(), encrypt(workspace, candidate.apiKey()));
        Path tmp = Files.createTempFile(root, "model-", ".tmp");
        try {
            privateFile(tmp); mapper.writeValue(tmp.toFile(), stored);
            try { Files.move(tmp, path(workspace), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, path(workspace), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
        return view(workspace);
    }
    public synchronized View delete(String workspace) throws IOException { Files.deleteIfExists(path(workspace)); return view(workspace); }
    private String encrypt(String workspace, String plaintext) {
        try {
            byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(workspace.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = Arrays.copyOf(nonce, nonce.length + ciphertext.length);
            System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) { throw new IllegalStateException("密钥保存失败。"); }
    }
    private String decrypt(String workspace, String encrypted) {
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(128, Arrays.copyOf(combined, 12)));
            cipher.updateAAD(workspace.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(combined, 12, combined.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("无法解密当前工作区的密钥，请重新保存模型设置。"); }
    }
}
