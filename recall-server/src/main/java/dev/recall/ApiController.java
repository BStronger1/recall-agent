package dev.recall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    public record ItemInput(@Size(max=36) String id, @NotBlank @Size(max=100) String title,
        @NotBlank @Size(max=16000) String content, @Pattern(regexp="preference|fact|project|document") @NotNull String kind) {}
    public record ChatInput(@NotBlank @Size(max=6000) String message, @Pattern(regexp="none|local|dify|ragflow") @NotNull String provider, boolean useMemory, Boolean grounded) {}
    private final Store store; private final Retrieval retrieval; private final AgentService agent; private final Environment env; private final SemanticSearch search;
    public ApiController(Store store, Retrieval retrieval, AgentService agent, Environment env, SemanticSearch search) { this.store = store; this.retrieval = retrieval; this.agent = agent; this.env = env; this.search = search; }
    @GetMapping("/health") public Map<String,String> health() { return Map.of("status", "ok", "product", "Recall Agent"); }
    @GetMapping("/config") public Map<String,Object> config() { return Map.of("modelReady", !env.getProperty("recall.model-key", "").isBlank(), "accessReady", !env.getProperty("recall.access-token", "").isBlank(), "providers", retrieval.providers()); }
    @GetMapping("/workspace") public Store.State state(@RequestHeader("X-Workspace-Key") String workspace) throws IOException { return store.read(workspace); }
    @PostMapping("/{collection:memories|documents}") public Store.State save(@RequestHeader("X-Workspace-Key") String workspace, @PathVariable String collection, @Valid @RequestBody ItemInput input) throws IOException {
        var state = store.save(workspace, collection, input.id(), input.title(), input.content(), input.kind()); search.invalidate(workspace); return state;
    }
    @DeleteMapping("/{collection:memories|documents}/{id}") public Store.State delete(@RequestHeader("X-Workspace-Key") String workspace, @PathVariable String collection, @PathVariable String id) throws IOException { var state = store.delete(workspace, collection, id); search.invalidate(workspace); return state; }
    @DeleteMapping("/chat") public Store.State clear(@RequestHeader("X-Workspace-Key") String workspace) throws IOException { return store.clearChat(workspace); }
    @PostMapping("/chat") public AgentService.Answer chat(@RequestHeader("X-Workspace-Key") String workspace, @Valid @RequestBody ChatInput input) throws IOException { return agent.chat(workspace, input.message(), input.provider(), input.useMemory(), !Boolean.FALSE.equals(input.grounded())); }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class}) public ResponseEntity<?> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", e instanceof MethodArgumentNotValidException ? "请检查输入长度和必填字段。" : e.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> unavailable(IllegalStateException e) { return ResponseEntity.status(502).body(Map.of("error", e.getMessage())); }
    @ExceptionHandler(IOException.class) public ResponseEntity<?> io(IOException e) { return ResponseEntity.internalServerError().body(Map.of("error", "数据保存或读取失败，请联系站点维护者。")); }
}
