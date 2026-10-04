package dev.recall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/api/model-settings")
public class ModelController {
    public record Input(@NotBlank @Size(max=500) String baseUrl, @NotBlank @Size(max=160) String model, @Size(max=4096) String apiKey) {
        @Override public String toString() { return "Input[credentials redacted]"; }
    }
    private final ModelSettings settings;
    private final JsonHttp http;
    private final Semaphore tests = new Semaphore(2);
    private long minute = System.currentTimeMillis() / 60000;
    private int requests;
    public ModelController(ModelSettings settings, JsonHttp http) { this.settings = settings; this.http = http; }
    private synchronized boolean underQuota() {
        long now = System.currentTimeMillis() / 60000;
        if (minute != now) { minute = now; requests = 0; }
        return ++requests <= 10;
    }
    @GetMapping public ModelSettings.View get(@RequestHeader("X-Workspace-Key") String workspace) throws IOException { return settings.view(workspace); }
    @PutMapping public ModelSettings.View save(@RequestHeader("X-Workspace-Key") String workspace, @Valid @RequestBody Input input) throws IOException {
        return settings.save(workspace, input.baseUrl(), input.model(), input.apiKey());
    }
    @DeleteMapping public ModelSettings.View delete(@RequestHeader("X-Workspace-Key") String workspace) throws IOException { return settings.delete(workspace); }
    @PostMapping("/test") public Map<String,Object> test(@RequestHeader("X-Workspace-Key") String workspace, @Valid @RequestBody Input input) throws IOException {
        var candidate = settings.candidate(workspace, input.baseUrl(), input.model(), input.apiKey());
        if (!tests.tryAcquire()) throw new IllegalStateException("连接测试繁忙，请稍后重试。");
        try {
            if (!underQuota()) throw new IllegalStateException("连接测试已达到每分钟 10 次上限，请稍后重试。");
            var result = http.post(candidate.baseUrl() + "/chat/completions", candidate.apiKey(), Map.of(
                "model", candidate.model(), "messages", List.of(Map.of("role", "user", "content", "Reply only OK.")), "max_tokens", 16, "stream", false));
            if (result.path("choices").path(0).path("message").path("content").asText("").isBlank()) throw new IllegalStateException("模型未返回文本，请检查模型名称和接口兼容性。");
            return Map.of("ok", true, "model", candidate.model(), "message", "真实调用成功。测试不自动保存配置。");
        } finally { tests.release(); }
    }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class}) public ResponseEntity<?> bad(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", e instanceof MethodArgumentNotValidException ? "请检查 API 地址、模型名和密钥长度。" : e.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> upstream(IllegalStateException e) { return ResponseEntity.status(502).body(Map.of("error", e.getMessage())); }
    @ExceptionHandler(IOException.class) public ResponseEntity<?> storage(IOException e) { return ResponseEntity.internalServerError().body(Map.of("error", "模型配置存储失败，请联系维护者。")); }
}
