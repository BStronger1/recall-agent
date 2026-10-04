package dev.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Single-instance accounts. Password hashes and hashed, expiring sessions persist privately. */
@Service
public class Accounts {
    public static final long SESSION_SECONDS = 30L * 24 * 3600;
    record Session(String hash, long expires) {}
    record User(String username, String workspace, String salt, String passwordHash, List<Session> sessions) {}
    public record Identity(String username, String workspace) {}
    public record Login(Identity identity, String token) {
        @Override public String toString() { return "Login[redacted]"; }
    }
    private final Path root;
    private final ObjectMapper mapper;
    private final String legacyToken;
    private final int maxAccounts;
    private final Map<String, User> users = new HashMap<>();
    private final Map<String, long[]> attempts = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final byte[] dummySalt = new byte[16];

    public Accounts(@Value("${recall.data-dir}") String dir, ObjectMapper mapper,
                    @Value("${recall.access-token:}") String legacyToken,
                    @Value("${recall.max-accounts:1000}") int maxAccounts) throws IOException {
        this.root = Path.of(dir).resolve("accounts"); this.mapper = mapper;
        this.legacyToken = legacyToken; this.maxAccounts = maxAccounts;
        Files.createDirectories(root); random.nextBytes(dummySalt);
        if (Files.getFileStore(root).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        try (var files = Files.list(root)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).toList()) {
                User user = mapper.readValue(file.toFile(), User.class);
                users.put(user.username(), user);
            }
        }
    }
    private String randomHex(int bytes) { byte[] data = new byte[bytes]; random.nextBytes(data); return HexFormat.of().formatHex(data); }
    private static String digest(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("认证服务不可用。"); }
    }
    private static String passwordHash(String password, byte[] salt) {
        var spec = new PBEKeySpec(password.toCharArray(), salt, 600_000, 256);
        try { return HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded()); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("认证服务不可用。"); }
        finally { spec.clearPassword(); }
    }
    private static boolean equalsSecret(String a, String b) {
        return b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
    private static String username(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{3,32}")) throw new IllegalArgumentException("用户名须为 3–32 位字母、数字或下划线。");
        return value.toLowerCase(Locale.ROOT);
    }
    private void limit(String key, int max) {
        long now = Instant.now().getEpochSecond();
        attempts.entrySet().removeIf(e -> e.getValue()[0] < now);
        if (!attempts.containsKey(key) && attempts.size() >= 5000) throw new Limited();
        long[] counter = attempts.computeIfAbsent(key, k -> new long[]{now + 3600, 0});
        if (++counter[1] > max) throw new Limited();
    }
    public static class Limited extends RuntimeException {}
    public static class InvalidLogin extends RuntimeException {}
    private void write(User user) throws IOException {
        Path tmp = Files.createTempFile(root, "account-", ".tmp");
        try {
            if (Files.getFileStore(tmp).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            mapper.writeValue(tmp.toFile(), user);
            try { Files.move(tmp, root.resolve(user.workspace() + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, root.resolve(user.workspace() + ".json"), StandardCopyOption.REPLACE_EXISTING); }
            users.put(user.username(), user);
        } finally { Files.deleteIfExists(tmp); }
    }
    private Login session(User user) throws IOException {
        String token = randomHex(32); long now = Instant.now().getEpochSecond();
        var sessions = new ArrayList<>(user.sessions().stream().filter(s -> s.expires() > now).toList());
        while (sessions.size() >= 10) sessions.removeFirst();
        sessions.add(new Session(digest(token), now + SESSION_SECONDS));
        write(new User(user.username(), user.workspace(), user.salt(), user.passwordHash(), sessions));
        return new Login(new Identity(user.username(), user.workspace()), token);
    }
    public synchronized Login register(String name, String password, String priorWorkspace, String priorToken, String ip) throws IOException {
        limit("register-global", 100); limit("register-ip:" + ip, 10);
        name = username(name);
        if (password == null || password.length() < 12 || password.length() > 128) throw new IllegalArgumentException("密码须为 12–128 位，请使用独立的长密码。");
        if (users.containsKey(name)) throw new IllegalArgumentException("该用户名不可用，请选择其他用户名。");
        if (users.size() >= maxAccounts) throw new IllegalArgumentException("当前站点账号名额已满，请联系维护者。");
        String workspace = randomHex(32);
        if (priorWorkspace != null && !priorWorkspace.isBlank()) {
            if (legacyToken.isBlank() || !equalsSecret(legacyToken, priorToken) || !priorWorkspace.matches("[a-f0-9]{64}") || owns(priorWorkspace))
                throw new IllegalArgumentException("旧工作区凭据无效，或已绑定账号。");
            workspace = priorWorkspace;
        }
        String salt = randomHex(16);
        return session(new User(name, workspace, salt, passwordHash(password, HexFormat.of().parseHex(salt)), List.of()));
    }
    public synchronized Login login(String name, String password, String ip) throws IOException {
        limit("login-global", 600); limit("login-ip:" + ip, 100);
        name = username(name); limit("login-user:" + name, 30);
        if (password == null || password.length() > 128) throw new InvalidLogin();
        User user = users.get(name);
        String hash = passwordHash(password, user == null ? dummySalt : HexFormat.of().parseHex(user.salt()));
        if (user == null || !equalsSecret(user.passwordHash(), hash)) throw new InvalidLogin();
        attempts.remove("login-user:" + name);
        return session(user);
    }
    public synchronized Identity authenticate(String token) {
        if (token == null || !token.matches("[a-f0-9]{64}")) return null;
        String hash = digest(token); long now = Instant.now().getEpochSecond();
        return users.values().stream().filter(u -> u.sessions().stream().anyMatch(s -> s.expires() > now && equalsSecret(s.hash(), hash)))
            .findFirst().map(u -> new Identity(u.username(), u.workspace())).orElse(null);
    }
    public synchronized void logout(String token) throws IOException {
        Identity identity = authenticate(token);
        if (identity == null) return;
        User user = users.get(identity.username()); String hash = digest(token);
        write(new User(user.username(), user.workspace(), user.salt(), user.passwordHash(), user.sessions().stream().filter(s -> !equalsSecret(s.hash(), hash)).toList()));
    }
    public synchronized boolean owns(String workspace) { return users.values().stream().anyMatch(u -> u.workspace().equals(workspace)); }
}
