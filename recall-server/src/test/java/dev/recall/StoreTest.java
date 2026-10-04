package dev.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class StoreTest {
    @TempDir Path temp;
    String a = "a".repeat(64), b = "b".repeat(64);
    Store open() throws Exception { return new Store(temp.toString(), new ObjectMapper()); }
    @Test void persistsAndIsolatesWorkspaces() throws Exception {
        var store = open(); store.save(a, "memories", null, "语言", "请用中文回答", "preference");
        assertEquals(1, open().read(a).memories().size());
        assertTrue(open().read(b).memories().isEmpty());
    }
    @Test void updatesAndDeletesWithoutLeavingDuplicateMemories() throws Exception {
        var store = open(); var first = store.save(a, "memories", null, "偏好", "详细", "preference").memories().getFirst();
        store.save(a, "memories", first.id(), "偏好", "简短", "preference");
        assertEquals(1, open().read(a).memories().size());
        assertEquals("简短", open().read(a).memories().getFirst().content());
        store.delete(a, "memories", first.id()); assertTrue(open().read(a).memories().isEmpty());
    }
    @Test void rejectsPathsAndCrossWorkspaceUpdates() throws Exception {
        var store = open(); assertThrows(IllegalArgumentException.class, () -> store.read("../private"));
        var item = store.save(a, "memories", null, "A", "A", "fact").memories().getFirst();
        assertThrows(IllegalArgumentException.class, () -> store.save(b, "memories", item.id(), "B", "B", "fact"));
    }
    @Test void boundsHistoryAndClearsOnlyConversation() throws Exception {
        var store = open(); store.save(a, "memories", null, "偏好", "中文", "preference");
        for (int i = 0; i < 15; i++) store.append(a, "Q" + i, "A" + i);
        assertEquals(20, open().read(a).messages().size());
        assertEquals("Q5", open().read(a).messages().getFirst().content());
        store.clearChat(a); assertTrue(open().read(a).messages().isEmpty()); assertEquals(1, open().read(a).memories().size());
    }
}
