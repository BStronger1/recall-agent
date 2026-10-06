package dev.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SemanticSearchTest {
    @TempDir Path temp; ObjectMapper mapper = new ObjectMapper(); JsonHttp http = mock(JsonHttp.class);
    String a = "a".repeat(64), b = "b".repeat(64);
    SemanticSearch search() throws Exception { return new SemanticSearch(temp.toString(),mapper,new MockEnvironment().withProperty("recall.model-key","site-key"),http); }
    void vectors() throws Exception {
        when(http.post(anyString(),anyString(),any())).thenAnswer(invocation -> {
            var body = (Map<?,?>) invocation.getArgument(2); var input = (List<?>)body.get("input");
            var root = mapper.createObjectNode(); var rows = root.putArray("data");
            for(int i=0;i<input.size();i++) rows.addObject().put("index",i).putArray("embedding").add(1).add(0);
            return root;
        });
    }
    @Test void unconfiguredWorkspaceNeverBorrowsSiteEmbeddingCredentials() throws Exception {
        var s = search(); assertFalse(s.view(a).keyConfigured());
        assertTrue(s.search(a,"花费上限",List.of(new SemanticSearch.Candidate("m","预算","7300 元"))).hits().isEmpty());
        verifyNoInteractions(http);
    }
    @Test void semanticMatchWithoutKeywordsUsesPersonalConfigAndIsolatesCache() throws Exception {
        vectors(); var s = search(); s.save(a,"https://www.dmxapi.cn/v1","embedding","personal-key");
        var docs = List.of(new SemanticSearch.Candidate("m","预算","7300 元"));
        assertEquals("m",s.search(a,"花费上限",docs).hits().getFirst().id());
        assertTrue(s.search(b,"花费上限",docs).hits().isEmpty());
        assertFalse(Files.readString(temp.resolve("embeddings").resolve(a+".model.json")).contains("personal-key"));
        clearInvocations(http); s.search(a,"花费上限",docs); verify(http,times(1)).post(anyString(),eq("personal-key"),any());
        clearInvocations(http); s.search(a,"花费上限",List.of(new SemanticSearch.Candidate("m","预算","9000 元"))); verify(http,times(2)).post(anyString(),anyString(),any());
    }
    @Test void vectorFailureFallsBackWithVisibleWarningAndRejectsMalformedVectors() throws Exception {
        vectors(); var s = search(); s.save(a,"https://www.dmxapi.cn/v1","embedding","personal-key");
        doThrow(new IllegalStateException("offline")).when(http).post(anyString(),anyString(),any());
        var r = s.search(a,"预算",List.of(new SemanticSearch.Candidate("m","预算","7300 元")));
        assertEquals("m",r.hits().getFirst().id()); assertTrue(r.warning().contains("回退"));
        doReturn(mapper.readTree("{\"data\":[{\"index\":0,\"embedding\":[0,0]}]}")).when(http).post(anyString(),anyString(),any());
        assertThrows(IllegalStateException.class, () -> s.save(b,"https://www.dmxapi.cn/v1","bad","personal-key")); assertFalse(s.view(b).custom());
    }
}
