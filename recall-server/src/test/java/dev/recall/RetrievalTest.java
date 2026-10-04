package dev.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetrievalTest {
    @Test void findsChineseAndEnglishAndRejectsUnrelatedText() {
        var docs = List.of(new Store.Item("1", "Java 学习", "Java Spring Boot 后端开发与长期记忆管理。", "document", ""), new Store.Item("2", "食谱", "番茄炒蛋做法。", "document", ""));
        assertEquals("Java 学习", Retrieval.local("长期记忆", docs).getFirst().title());
        assertEquals(1, Retrieval.local("spring", docs).size());
        assertTrue(Retrieval.local("天文学", docs).isEmpty());
        assertTrue(Retrieval.local("", docs).isEmpty());
    }
    @Test void validatesProvidersAndNormalizesDifyAndRagflow() throws Exception {
        var http = mock(JsonHttp.class);
        var env = new MockEnvironment().withProperty("recall.dify-url", "https://dify.test/v1").withProperty("recall.dify-key", "test").withProperty("recall.dify-dataset", "ds")
            .withProperty("recall.ragflow-url", "https://rag.test").withProperty("recall.ragflow-key", "test").withProperty("recall.ragflow-dataset", "ds");
        var retrieval = new Retrieval(env, http);
        var mapper = new ObjectMapper();
        when(http.post(eq("https://dify.test/v1/datasets/ds/retrieve"), eq("test"), any())).thenReturn(mapper.readTree("{\"records\":[{\"score\":0.8,\"segment\":{\"content\":\"evidence\",\"document\":{\"name\":\"spec\"}}}]}"));
        var result = retrieval.retrieve("dify", "query", List.of()); assertEquals("spec", result.getFirst().title()); assertEquals("K1", result.getFirst().id());
        when(http.post(eq("https://rag.test/api/v1/retrieval"), eq("test"), any())).thenReturn(mapper.readTree("{\"code\":0,\"data\":{\"chunks\":[{\"content\":\"evidence\",\"document_keyword\":\"manual\",\"similarity\":0.9}]}}"));
        assertEquals("manual", retrieval.retrieve("ragflow", "query", List.of()).getFirst().title());
        assertThrows(IllegalArgumentException.class, () -> retrieval.retrieve("unknown", "q", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Retrieval(new MockEnvironment(), http).retrieve("dify", "q", List.of()));
        when(http.post(eq("https://rag.test/api/v1/retrieval"), eq("test"), any())).thenReturn(mapper.readTree("{\"code\":102,\"message\":\"bad\"}"));
        assertThrows(IllegalStateException.class, () -> retrieval.retrieve("ragflow", "q", List.of()));
    }
}
