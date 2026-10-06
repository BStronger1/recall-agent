package dev.recall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/api")
public class MemoryController {
    public record Policy(boolean autoExtract) {}
    public record Decision(boolean accept) {}
    public record SearchInput(@NotBlank @Size(max=500) String baseUrl, @NotBlank @Size(max=160) String model, @Size(max=4096) String apiKey, @DecimalMin("0") @DecimalMax("1") Double minSimilarity) {
        @Override public String toString() { return "SearchInput[credentials redacted]"; }
    }
    private final Store store; private final SemanticSearch search;
    private final Semaphore slots = new Semaphore(2);
    private long minute; private int requests;
    public MemoryController(Store store, SemanticSearch search) { this.store = store; this.search = search; }
    private synchronized boolean quota() {
        long now = System.currentTimeMillis()/60000;
        if (minute != now) { minute = now; requests = 0; }
        return ++requests <= 10;
    }
    @GetMapping("/retrieval-settings") public SemanticSearch.View view(@RequestHeader("X-Workspace-Key") String w) throws IOException { return search.view(w); }
    @PutMapping("/retrieval-settings") public SemanticSearch.View save(@RequestHeader("X-Workspace-Key") String w, @Valid @RequestBody SearchInput input) throws IOException {
        if (!slots.tryAcquire()) throw new IllegalStateException("向量配置检查繁忙，请稍后重试。");
        try {
            if (!quota()) throw new IllegalStateException("每分钟最多检查 10 次向量配置。");
            return search.save(w, input.baseUrl(), input.model(), input.apiKey(), input.minSimilarity() == null ? .55 : input.minSimilarity());
        } finally { slots.release(); }
    }
    @DeleteMapping("/retrieval-settings") public SemanticSearch.View delete(@RequestHeader("X-Workspace-Key") String w) throws IOException { return search.delete(w); }
    @PutMapping("/memory-policy") public Store.State policy(@RequestHeader("X-Workspace-Key") String w, @RequestBody Policy p) throws IOException { return store.policy(w, p.autoExtract()); }
    @PostMapping("/memory-proposals/{id}") public Store.State decide(@RequestHeader("X-Workspace-Key") String w, @PathVariable String id, @RequestBody Decision d) throws IOException {
        var state = store.decide(w, id, d.accept()); search.invalidate(w); return state;
    }
    @PostMapping("/memory-revisions/{id}/undo") public Store.State undo(@RequestHeader("X-Workspace-Key") String w, @PathVariable String id) throws IOException {
        var state = store.undo(w, id); search.invalidate(w); return state;
    }
}
