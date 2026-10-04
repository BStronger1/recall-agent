package dev.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountsTest {
    @TempDir Path temp;
    final ObjectMapper mapper = new ObjectMapper();
    final String password = "unique-test-password-123";
    @Test void hashesPasswordsAndSessionsAndRestoresAfterRestart() throws Exception {
        var accounts = new Accounts(temp.toString(), mapper, "legacy-token", 1000);
        var first = accounts.register("Alice", password, null, null, "ip1");
        String saved = Files.readString(temp.resolve("accounts").resolve(first.identity().workspace() + ".json"));
        assertFalse(saved.contains(password)); assertFalse(saved.contains(first.token()));
        var restored = new Accounts(temp.toString(), mapper, "legacy-token", 1000);
        assertEquals(first.identity(), restored.authenticate(first.token()));
        var second = restored.login("ALICE", password, "ip2");
        assertEquals(first.identity(), second.identity()); assertNotEquals(first.token(), second.token());
        restored.logout(first.token());
        assertNull(restored.authenticate(first.token())); assertNotNull(restored.authenticate(second.token()));
        assertThrows(Accounts.InvalidLogin.class, () -> restored.login("Alice", "wrong-password", "ip3"));
        assertThrows(IllegalArgumentException.class, () -> restored.register("alice", password, null, null, "ip4"));
        var tree = mapper.readTree(Files.readString(temp.resolve("accounts").resolve(first.identity().workspace() + ".json")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("sessions").get(0)).put("expires", 0);
        mapper.writeValue(temp.resolve("accounts").resolve(first.identity().workspace() + ".json").toFile(), tree);
        assertNull(new Accounts(temp.toString(), mapper, "legacy-token", 1000).authenticate(second.token()));
    }
    @Test void rateLimitsRegistrationAndValidatesMigrationProof() throws Exception {
        var accounts = new Accounts(temp.toString(), mapper, "legacy-token", 1000);
        String source = "a".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> accounts.register("alice", password, source, "wrong", "ip"));
        var migrated = accounts.register("alice", password, source, "legacy-token", "ip");
        assertEquals(source, migrated.identity().workspace());
        assertThrows(IllegalArgumentException.class, () -> accounts.register("bob", password, source, "legacy-token", "ip"));
        for (int i=0; i<7; i++) assertThrows(IllegalArgumentException.class, () -> accounts.register("bad", "short", null, null, "ip"));
        assertThrows(Accounts.Limited.class, () -> accounts.register("newuser", password, null, null, "ip"));
    }
}
