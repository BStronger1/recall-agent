package dev.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelSettingsTest {
    @TempDir Path temp;
    final ObjectMapper mapper = new ObjectMapper();
    final MockEnvironment env = new MockEnvironment().withProperty("recall.model-key", "site-key-only")
        .withProperty("recall.model-url", "https://api.openai.com/v1").withProperty("recall.model", "site-model");
    final String a = "a".repeat(64), b = "b".repeat(64), key = "personal-test-key";
    ModelSettings settings() throws Exception { return new ModelSettings(temp.toString(), mapper, env); }

    @Test void encryptsPersistsIsolatesAndDeletesCredentials() throws Exception {
        var settings = settings();
        var view = settings.save(a, "https://api.openai.com/v1/", "personal-model", key);
        assertTrue(view.custom());
        assertFalse(mapper.writeValueAsString(view).contains(key));
        String disk = Files.readString(temp.resolve(a + ".model.json"));
        assertFalse(disk.contains(key));
        assertEquals(key, settings().effective(a).apiKey());
        assertEquals("site-key-only", settings.effective(b).apiKey());
        assertFalse(settings.view(b).custom());
        Files.copy(temp.resolve(a + ".model.json"), temp.resolve(b + ".model.json"));
        assertThrows(IllegalStateException.class, () -> settings.effective(b));
        assertFalse(settings.delete(a).custom());
        assertFalse(Files.exists(temp.resolve(a + ".model.json")));
        assertEquals("site-model", settings.effective(a).model());
    }

    @Test void preservesPersonalKeyOnlyForSameEndpoint() throws Exception {
        var settings = settings();
        assertThrows(IllegalArgumentException.class, () -> settings.candidate(a, "https://api.openai.com/v1", "model", ""));
        settings.save(a, "https://api.openai.com/v1", "first", key);
        settings.save(a, "https://api.openai.com/v1/", "second", "");
        assertEquals(key, settings.effective(a).apiKey());
        assertEquals("second", settings.effective(a).model());
        assertThrows(IllegalArgumentException.class, () -> settings.save(a, "https://api.deepseek.com/v1", "model", ""));
        assertEquals("second", settings.effective(a).model());
    }

    @Test void restrictsDestinationsAndRejectsMalformedInputs() throws Exception {
        var settings = settings();
        for (String url : new String[]{"http://api.openai.com/v1", "https://127.0.0.1/v1", "https://api.openai.com.evil.test/v1",
            "https://api.openai.com@evil.test/v1", "https://api.openai.com:8443/v1", "https://api.openai.com/v1?key=x",
            "https://api.openai.com/v1#x", "https://api.openai.com/%2e%2e/v1", "https://api.openai.com/../v1"}) {
            assertThrows(IllegalArgumentException.class, () -> settings.candidate(a, url, "model", key), url);
        }
        assertThrows(IllegalArgumentException.class, () -> settings.save("../bad", "https://api.openai.com/v1", "model", key));
        assertThrows(IllegalArgumentException.class, () -> settings.save(a, "https://api.openai.com/v1", "model", "key with spaces"));
    }

    @Test void connectionTestUsesSubmittedCredentialsWithoutSaving() throws Exception {
        var settings = settings(); var http = mock(JsonHttp.class);
        when(http.post(anyString(), anyString(), any())).thenReturn(mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"));
        var controller = new ModelController(settings, http);
        assertEquals(true, controller.test(a, new ModelController.Input("https://api.openai.com/v1", "my-model", key)).get("ok"));
        verify(http).post(eq("https://api.openai.com/v1/chat/completions"), eq(key), argThat(body -> ((Map<?,?>) body).get("model").equals("my-model")));
        assertFalse(settings.view(a).custom());
        assertFalse(Files.exists(temp.resolve(a + ".model.json")));
    }
}
