package dev.recall;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AccessFilter extends OncePerRequestFilter {
    private final String token;
    private final Accounts accounts;
    public AccessFilter(@Value("${recall.access-token}") String token, Accounts accounts) { this.token = token; this.accounts = accounts; }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "no-referrer");
        String path = req.getRequestURI();
        if (!path.startsWith("/api/") || path.equals("/api/health") || path.equals("/api/config")) { chain.doFilter(req, res); return; }
        res.setHeader("Cache-Control", "no-store");
        if (req.getContentLengthLong() > 100_000) { res.sendError(413); return; }
        boolean safeMethod = req.getMethod().equals("GET") || req.getMethod().equals("HEAD");
        boolean authPath = path.startsWith("/api/auth/");
        var identity = accounts.authenticate(AuthController.token(req));
        // Custom headers require a same-origin request (no CORS is enabled). Cookies are also SameSite=Strict.
        if (!safeMethod && (authPath || identity != null) && !"web".equals(req.getHeader("X-Recall-Client"))) {
            error(res, 403, "请求来源校验失败，请刷新页面。"); return;
        }
        if (path.equals("/api/auth/register") || path.equals("/api/auth/login") || path.equals("/api/auth/logout")) {
            chain.doFilter(req, res); return;
        }
        if (identity != null) {
            req.setAttribute("recall.identity", identity);
            chain.doFilter(new HttpServletRequestWrapper(req) {
                @Override public String getHeader(String name) { return name.equalsIgnoreCase("X-Workspace-Key") ? identity.workspace() : super.getHeader(name); }
                @Override public java.util.Enumeration<String> getHeaders(String name) {
                    return name.equalsIgnoreCase("X-Workspace-Key") ? java.util.Collections.enumeration(java.util.List.of(identity.workspace())) : super.getHeaders(name);
                }
            }, res);
            return;
        }
        if (authPath) { error(res, 401, "请先登录账号。"); return; }
        String provided = req.getHeader("Authorization");
        if (token.isBlank() || provided == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            error(res, 401, "请先注册或登录账号。"); return;
        }
        String workspace = req.getHeader("X-Workspace-Key");
        if (workspace == null || !workspace.matches("[a-f0-9]{64}")) { res.sendError(400); return; }
        if (accounts.owns(workspace)) { error(res, 401, "此工作区已绑定账号，请使用账号登录。"); return; }
        chain.doFilter(req, res);
    }
    private void error(HttpServletResponse res, int code, String message) throws IOException {
        res.setStatus(code); res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
