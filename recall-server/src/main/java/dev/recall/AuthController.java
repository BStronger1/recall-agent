package dev.recall;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record Credentials(@NotBlank @Size(max=32) String username, @NotBlank @Size(max=128) String password,
                              @Size(max=64) String legacyWorkspace, @Size(max=4096) String legacyToken) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }
    private final Accounts accounts;
    public AuthController(Accounts accounts) { this.accounts = accounts; }
    static String token(HttpServletRequest req) {
        if (req.getCookies() != null) for (Cookie cookie : req.getCookies()) if (cookie.getName().equals("recall_session")) return cookie.getValue();
        return null;
    }
    private void cookie(HttpServletRequest req, HttpServletResponse res, String token, long age) {
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("recall_session", token).httpOnly(true).secure(req.isSecure())
            .sameSite("Strict").path("/api").maxAge(age).build().toString());
    }
    private Map<String,Object> signedIn(Accounts.Login login, HttpServletRequest req, HttpServletResponse res) {
        cookie(req, res, login.token(), Accounts.SESSION_SECONDS);
        return Map.of("username", login.identity().username(), "authenticated", true);
    }
    @PostMapping("/register") public Map<String,Object> register(@Valid @RequestBody Credentials input, HttpServletRequest req, HttpServletResponse res) throws IOException {
        return signedIn(accounts.register(input.username(), input.password(), input.legacyWorkspace(), input.legacyToken(), req.getRemoteAddr()), req, res);
    }
    @PostMapping("/login") public Map<String,Object> login(@Valid @RequestBody Credentials input, HttpServletRequest req, HttpServletResponse res) throws IOException {
        return signedIn(accounts.login(input.username(), input.password(), req.getRemoteAddr()), req, res);
    }
    @GetMapping("/me") public Map<String,Object> me(HttpServletRequest req) {
        var identity = (Accounts.Identity) req.getAttribute("recall.identity");
        return Map.of("username", identity.username(), "authenticated", true);
    }
    @PostMapping("/logout") public Map<String,Boolean> logout(HttpServletRequest req, HttpServletResponse res) throws IOException {
        accounts.logout(token(req)); cookie(req, res, "", 0); return Map.of("ok", true);
    }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class}) public ResponseEntity<?> invalid(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", e instanceof MethodArgumentNotValidException ? "请检查用户名、密码长度和必填项。" : e.getMessage()));
    }
    @ExceptionHandler(Accounts.InvalidLogin.class) public ResponseEntity<?> denied() { return ResponseEntity.status(401).body(Map.of("error", "用户名或密码不正确。")); }
    @ExceptionHandler(Accounts.Limited.class) public ResponseEntity<?> limited() { return ResponseEntity.status(429).body(Map.of("error", "尝试次数过多，请一小时后重试。")); }
    @ExceptionHandler(IOException.class) public ResponseEntity<?> storage() { return ResponseEntity.internalServerError().body(Map.of("error", "账号保存失败，请稍后重试。")); }
}
