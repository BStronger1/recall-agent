package dev.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentServiceTest {
    @TempDir Path temp;
    @Test void emptyGroundedContextDoesNotAskModelToInventAnAnswer() throws Exception {
        var mapper = new ObjectMapper(); var store = new Store(temp.toString(), mapper); var http = mock(JsonHttp.class);
        var env = new MockEnvironment().withProperty("recall.model-key", "test").withProperty("recall.model-url", "https://model.test/v1").withProperty("recall.model", "test-model");
        var agent = new AgentService(store, new Retrieval(env,http), http,env,new ModelSettings(temp.toString(),mapper,env),new SemanticSearch(temp.toString(),mapper,env,http),new Reliability(http,mapper),new MemoryExtractor(http,mapper,store));
        var answer = agent.chat("e".repeat(64),"这次花费最多能到多少？","none",true);
        assertEquals("no_evidence",answer.verification()); assertFalse(answer.answer().contains("5000")); verifyNoInteractions(http);
    }
    @Test void includesEvidencePersistsAnswerAndHonorsMemoryToggle() throws Exception {
        var mapper = new ObjectMapper(); var store = new Store(temp.toString(), mapper); var http = mock(JsonHttp.class);
        var env = new MockEnvironment().withProperty("recall.model-key", "test").withProperty("recall.model-url", "https://model.test/v1").withProperty("recall.model", "test-model");
        String workspace = "d".repeat(64);
        store.save(workspace, "memories", null, "language", "Chinese", "preference");
        store.save(workspace, "documents", null, "Java", "Java is a programming language", "document");
        when(http.post(anyString(), anyString(), any())).thenReturn(mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"answer [K1]\"}}]}"));
        var settings = new ModelSettings(temp.toString(), mapper, env);
        settings.save(workspace, "https://api.openai.com/v1", "personal-model", "personal-test-key");
        var agent = new AgentService(store, new Retrieval(env, http), http, env, settings, new SemanticSearch(temp.toString(), mapper, env, http), new Reliability(http, mapper), new MemoryExtractor(http, mapper, store));
        var answer = agent.chat(workspace, "Java", "local", true, false);
        verify(http).post(eq("https://api.openai.com/v1/chat/completions"), eq("personal-test-key"), argThat(body -> ((Map<?,?>) body).get("model").equals("personal-model")));
        assertEquals(1, answer.memories().size()); assertEquals(1, answer.sources().size()); assertEquals(2, store.read(workspace).messages().size());
        assertTrue(agent.chat(workspace, "Java", "none", false).memories().isEmpty());
        when(http.post(anyString(), anyString(), any())).thenThrow(new IllegalStateException("offline"));
        assertThrows(IllegalStateException.class, () -> agent.chat(workspace, "Java", "local", true));
        assertEquals(4, store.read(workspace).messages().size());
    }
}
