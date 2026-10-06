package dev.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReliabilityTest {
    ObjectMapper mapper = new ObjectMapper(); JsonHttp http = mock(JsonHttp.class);
    ModelSettings.Effective model = new ModelSettings.Effective("https://api.openai.com/v1", "test", "test-key");
    @Test void blocksFabricatedCitationWithoutSpendingAnotherRequest() {
        var result = new Reliability(http, mapper).check(model, "预算？", "", List.of(), "5000 元 [M1][K1]", Set.of(), true);
        assertEquals("blocked", result.status()); assertFalse(result.answer().contains("5000")); verifyNoInteractions(http);
    }
    @Test void rejectsUnsupportedAmountEvenWhenCitationIdExists() throws Exception {
        when(http.post(anyString(), anyString(), any())).thenReturn(mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"{\\\"supported\\\":false}\"}}]}"));
        var result = new Reliability(http, mapper).check(model, "预算？", "[M1] 预算 7300 元", List.of(), "5000 元 [M1]", Set.of("M1"), true);
        assertEquals("blocked", result.status()); assertFalse(result.answer().contains("5000"));
    }
    @Test void malformedOrUnavailableVerifierFailsClosed() throws Exception {
        when(http.post(anyString(), anyString(), any())).thenReturn(mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"yes\"}}]}"));
        assertEquals("unverified", new Reliability(http, mapper).check(model, "x", "x", List.of(), "claim", Set.of(), true).status());
        when(http.post(anyString(), anyString(), any())).thenThrow(new IllegalStateException("offline"));
        assertEquals("unverified", new Reliability(http, mapper).check(model, "x", "x", List.of(), "claim", Set.of(), true).status());
    }
    @Test void onlyUserHistoryIsAcceptedAsEvidenceAndGeneralModeIsLabelled() throws Exception {
        when(http.post(anyString(), anyString(), any())).thenReturn(mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"{\\\"supported\\\":true}\"}}]}"));
        var guard = new Reliability(http, mapper);
        var checked = guard.check(model, "预算？", "", List.of(new Store.Message("assistant", "invented-secret"), new Store.Message("user", "预算 7300")), "7300", Set.of(), true);
        assertEquals("model_checked", checked.status());
        verify(http).post(anyString(), anyString(), argThat(body -> !body.toString().contains("invented-secret") && body.toString().contains("预算 7300")));
        assertEquals("citations_only", guard.check(model, "hello", "", List.of(), "hello", Set.of(), false).status());
    }
}
