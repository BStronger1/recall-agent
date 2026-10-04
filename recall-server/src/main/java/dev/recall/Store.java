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
    public record State(List<Item> memories, List<Item> documents, List<Message> messages) {
        public static State empty() { return new State(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()); }
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
        items.removeIf(i -> i.id().equals(actualId));
        items.add(new Item(actualId, title.strip(), content.strip(), kind, Instant.now().toString()));
        write(workspace, state); return state;
    }
    public synchronized State delete(String workspace, String collection, String id) throws IOException {
        State state = read(workspace);
        (collection.equals("memories") ? state.memories() : state.documents()).removeIf(i -> i.id().equals(id));
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
}
