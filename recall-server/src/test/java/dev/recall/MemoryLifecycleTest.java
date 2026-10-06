package dev.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryLifecycleTest {
    @TempDir Path temp;
    String a = "a".repeat(64), b = "b".repeat(64);
    Store store() throws Exception { return new Store(temp.toString(), new ObjectMapper()); }
    Store.Proposal proposal(Store.Item before) { return new Store.Proposal("proposal", before, "预算", "9000 元", "fact", "预算改为 9000 元", "2026-10-06T00:00:00Z"); }
    @Test void migratesOldStateAndRequiresExplicitOptInAndReview() throws Exception {
        Files.writeString(temp.resolve(a+".json"), "{\"memories\":[],\"documents\":[],\"messages\":[]}");
        var s = store(); assertFalse(s.read(a).autoExtract());
        s.propose(a, List.of(proposal(null))); assertTrue(s.read(a).proposals().isEmpty());
        s.policy(a, true); s.propose(a, List.of(proposal(null))); s.propose(a, List.of(proposal(null)));
        assertEquals(1, store().read(a).proposals().size()); assertTrue(s.read(a).memories().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> s.decide(b, "proposal", true));
        var accepted = s.decide(a, "proposal", true);
        assertEquals("9000 元", accepted.memories().getFirst().content()); assertTrue(accepted.proposals().isEmpty());
        s.undo(a, accepted.revisions().getFirst().id()); assertTrue(s.read(a).memories().isEmpty());
    }
    @Test void updateIsTraceableAndUndoRestoresPriorContent() throws Exception {
        var s = store(); var before = s.save(a,"memories",null,"预算","7300 元","fact").memories().getFirst();
        s.policy(a,true); s.propose(a,List.of(proposal(before))); var accepted = s.decide(a,"proposal",true);
        var revision = accepted.revisions().getLast(); assertEquals("预算改为 9000 元",revision.sourceQuote());
        assertEquals(1,accepted.memories().size());
        var restored = s.undo(a, revision.id()); assertEquals("7300 元",restored.memories().getFirst().content());
        assertThrows(IllegalArgumentException.class, () -> s.undo(a,revision.id()));
    }
    @Test void staleProposalAndStaleUndoCannotOverwriteNewContent() throws Exception {
        var s = store(); var before = s.save(a,"memories",null,"预算","7300 元","fact").memories().getFirst();
        String oldRevision = s.read(a).revisions().getFirst().id();
        s.policy(a,true); s.propose(a,List.of(proposal(before)));
        s.save(a,"memories",before.id(),"预算","10000 元","fact");
        assertThrows(IllegalArgumentException.class, () -> s.decide(a,"proposal",true));
        assertThrows(IllegalArgumentException.class, () -> s.undo(a,oldRevision));
        assertEquals("10000 元",s.read(a).memories().getFirst().content());
    }
    @Test void forgettingPurgesVersionsAndAssociatedPendingUpdate() throws Exception {
        var s = store(); var before = s.save(a,"memories",null,"预算","7300 元","fact").memories().getFirst();
        s.policy(a,true); s.propose(a,List.of(proposal(before)));
        var state = s.delete(a,"memories",before.id());
        assertTrue(state.memories().isEmpty()); assertTrue(state.revisions().isEmpty()); assertTrue(state.proposals().isEmpty());
        assertFalse(Files.readString(temp.resolve(a+".json")).contains("7300"));
    }
}
