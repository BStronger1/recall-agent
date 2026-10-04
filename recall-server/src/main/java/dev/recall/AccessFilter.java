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
    public AccessFilter(@Value("${recall.access-token}") String token) { this.token = token; }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "no-referrer");
        String path = req.getRequestURI();
        if (!path.startsWith("/api/") || path.equals("/api/health") || path.equals("/api/config")) { chain.doFilter(req, res); return; }
        res.setHeader("Cache-Control", "no-store");
        String provided = req.getHeader("Authorization");
        if (token.isBlank() || provided == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            res.setStatus(token.isBlank() ? 503 : 401); res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"error\":\"请先在设置中填写有效的访问口令；服务端需配置 RECALL_ACCESS_TOKEN。\"}"); return;
        }
        String workspace = req.getHeader("X-Workspace-Key");
        if (workspace == null || !workspace.matches("[a-f0-9]{64}")) { res.sendError(400); return; }
        if (req.getContentLengthLong() > 100_000) { res.sendError(413); return; }
        chain.doFilter(req, res);
    }
}
