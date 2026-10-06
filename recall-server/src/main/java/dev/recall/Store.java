package dev.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Single-instance persistent store. The browser's random workspace secret is the namespace. */
@Service
public class Store {
    public record Item(String id, String title, String content, String kind, String updatedAt) {}
    public record Message(String role, String content) {}
    public record Proposal(String id, Item before, String title, String content, String kind, String sourceQuote, String createdAt) {}
    public record Revision(String id, Item before, Item after, String sourceQuote, String changedAt, boolean undone) {}
    public record State(List<Item> memories, List<Item> documents, List<Message> messages,
                        List<Proposal> proposals, List<Revision> revisions, boolean autoExtract) {
        public State {
            memories = memories == null ? new ArrayList<>() : memories;
            documents = documents == null ? new ArrayList<>() : documents;
            messages = messages == null ? new ArrayList<>() : messages;
            proposals = proposals == null ? new ArrayList<>() : proposals;
            revisions = revisions == null ? new ArrayList<>() : revisions;
        }
        public static State empty() { return new State(null, null, null, null, null, false); }
    }
    private final Path root;
    private final ObjectMapper mapper;
    public Store(@Value("${recall.data-dir}") String dir, ObjectMapper mapper) throws IOException {
        root = Path.of(dir).toAbsolutePath().normalize(); this.mapper = mapper;
        Files.createDirectories(root);
    }
    private Path path(String workspace) {
        if (workspace == null || !workspace.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("工作区凭据无效，请刷新页面。");
        return root.resolve(workspace + ".json");
    }
    public synchronized State read(String workspace) throws IOException {
        Path file = path(workspace);
        return Files.exists(file) ? mapper.readValue(file.toFile(), State.class) : State.empty();
    }
    private void write(String workspace, State state) throws IOException {
        Path target = path(workspace), tmp = Files.createTempFile(root, "recall-", ".tmp");
        try {
            mapper.writeValue(tmp.toFile(), state);
            try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }
    public synchronized State save(String workspace, String collection, String id, String title, String content, String kind) throws IOException {
        State state = read(workspace);
        List<Item> items = collection.equals("memories") ? state.memories() : state.documents();
        if (id != null && items.stream().noneMatch(i -> i.id().equals(id))) throw new IllegalArgumentException("条目不存在。");
        if (id == null && items.size() >= 100) throw new IllegalArgumentException("每类最多保存 100 条，请先删除不再需要的内容。");
        String actualId = id == null ? UUID.randomUUID().toString() : id;
        Item before = items.stream().filter(i -> i.id().equals(actualId)).findFirst().orElse(null);
        Item after = new Item(actualId, title.strip(), content.strip(), kind, Instant.now().toString());
        items.removeIf(i -> i.id().equals(actualId));
        items.add(after);
        if (collection.equals("memories")) revision(state, before, after, "用户手动保存");
        write(workspace, state); return state;
    }
    public synchronized State delete(String workspace, String collection, String id) throws IOException {
        State state = read(workspace);
        (collection.equals("memories") ? state.memories() : state.documents()).removeIf(i -> i.id().equals(id));
        if (collection.equals("memories")) {
            // Forget removes historical content too; undo is for edits, not for explicit deletion.
            state.revisions().removeIf(r -> (r.before() != null && r.before().id().equals(id)) || (r.after() != null && r.after().id().equals(id)));
            state.proposals().removeIf(p -> p.before() != null && p.before().id().equals(id));
        }
        write(workspace, state); return state;
    }
    public synchronized void append(String workspace, String question, String answer) throws IOException {
        State state = read(workspace);
        state.messages().add(new Message("user", question)); state.messages().add(new Message("assistant", answer));
        while (state.messages().size() > 20) state.messages().removeFirst();
        write(workspace, state);
    }
    public synchronized State clearChat(String workspace) throws IOException {
        State state = read(workspace); state.messages().clear(); write(workspace, state); return state;
    }
    private static void revision(State state, Item before, Item after, String source) {
        state.revisions().add(new Revision(UUID.randomUUID().toString(), before, after, source, Instant.now().toString(), false));
        while (state.revisions().size() > 100) state.revisions().removeFirst();
    }
    public synchronized State policy(String workspace, boolean enabled) throws IOException {
        var s = read(workspace); var updated = new State(s.memories(), s.documents(), s.messages(), s.proposals(), s.revisions(), enabled);
        write(workspace, updated); return updated;
    }
    public synchronized void propose(String workspace, List<Proposal> proposals) throws IOException {
        var s = read(workspace);
        if (!s.autoExtract()) return;
        for (var p : proposals) {
            if (s.proposals().size() >= 20) break;
            if (s.memories().stream().anyMatch(m -> m.content().equals(p.content())) || s.proposals().stream().anyMatch(m -> m.content().equals(p.content()))) continue;
            s.proposals().add(p);
        }
        write(workspace, s);
    }
    public synchronized State decide(String workspace, String id, boolean accept) throws IOException {
        var s = read(workspace);
        var p = s.proposals().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("候选记忆不存在。"));
        if (accept) {
            Item current = p.before() == null ? null : s.memories().stream().filter(m -> m.id().equals(p.before().id())).findFirst().orElse(null);
            if (!Objects.equals(current, p.before())) throw new IllegalArgumentException("原记忆已发生变化，请丢弃候选并重新提取。");
            if (s.memories().stream().anyMatch(m -> m.content().equals(p.content()))) throw new IllegalArgumentException("相同内容已存在，请丢弃重复候选。");
            if (current == null && s.memories().size() >= 100) throw new IllegalArgumentException("记忆已达到 100 条上限。");
            var after = new Item(current == null ? UUID.randomUUID().toString() : current.id(), p.title(), p.content(), p.kind(), Instant.now().toString());
            s.memories().removeIf(m -> m.id().equals(after.id())); s.memories().add(after);
            revision(s, current, after, p.sourceQuote());
        }
        s.proposals().remove(p); write(workspace, s); return s;
    }
    public synchronized State undo(String workspace, String revisionId) throws IOException {
        var s = read(workspace);
        var r = s.revisions().stream().filter(x -> x.id().equals(revisionId)).findFirst().orElseThrow(() -> new IllegalArgumentException("版本记录不存在。"));
        if (r.undone()) throw new IllegalArgumentException("此变更已撤销。");
        Item current = s.memories().stream().filter(m -> m.id().equals(r.after().id())).findFirst().orElse(null);
        if (!Objects.equals(current, r.after())) throw new IllegalArgumentException("记忆已有后续修改，不能覆盖新版本。请编辑当前内容。");
        s.memories().removeIf(m -> m.id().equals(r.after().id()));
        if (r.before() != null) s.memories().add(new Item(r.before().id(), r.before().title(), r.before().content(), r.before().kind(), Instant.now().toString()));
        s.revisions().set(s.revisions().indexOf(r), new Revision(r.id(), r.before(), r.after(), r.sourceQuote(), r.changedAt(), true));
        write(workspace, s); return s;
    }
}
