package dev.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryExtractorTest {
    @TempDir Path temp;
    @Test void candidatesMustQuoteUserTextAndNeverWriteMemoryDirectly() throws Exception {
        var mapper = new ObjectMapper(); var store = new Store(temp.toString(),mapper); var http = mock(JsonHttp.class);
        String w = "a".repeat(64); store.policy(w,true);
        var response = mapper.createObjectNode(); response.putArray("choices").addObject().putObject("message").put("content", "[{\"title\":\"预算\",\"content\":\"7300元\",\"kind\":\"fact\",\"sourceQuote\":\"预算7300元\"},{\"title\":\"编造\",\"content\":\"secret\",\"sourceQuote\":\"不存在的原话\"}]");
        when(http.post(anyString(),anyString(),any())).thenReturn(response);
        new MemoryExtractor(http,mapper,store).extract(w,new ModelSettings.Effective("https://model.test","test","key"),"预算7300元",store.read(w));
        assertEquals(1,store.read(w).proposals().size()); assertTrue(store.read(w).memories().isEmpty());
    }
}
