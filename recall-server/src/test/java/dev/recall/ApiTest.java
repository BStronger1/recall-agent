package dev.recall;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Files;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"recall.access-token=test-only-token", "recall.model-key="})
@AutoConfigureMockMvc
class ApiTest {
    @Autowired MockMvc mvc;
    @DynamicPropertySource static void data(DynamicPropertyRegistry registry) throws Exception {
        String dir = Files.createTempDirectory("recall-test-").toString(); registry.add("recall.data-dir", () -> dir);
    }
    @Test void allowsPublicHealthButRejectsMissingOrWrongCredentials() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/workspace")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/workspace").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/workspace").header("Authorization", "Bearer test-only-token").header("X-Workspace-Key", "../x")).andExpect(status().isBadRequest());
    }
    @Test void validatesInputAndRoundTripsMemory() throws Exception {
        var headers = new org.springframework.http.HttpHeaders(); headers.setBearerAuth("test-only-token"); headers.set("X-Workspace-Key", "c".repeat(64));
        mvc.perform(post("/api/memories").headers(headers).contentType("application/json").content("{\"title\":\"\",\"content\":\"x\",\"kind\":\"fact\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/memories").headers(headers).contentType("application/json").content("{\"title\":\"language\",\"content\":\"Chinese\",\"kind\":\"preference\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.memories[0].content").value("Chinese"));
        mvc.perform(post("/api/chat").headers(headers).contentType("application/json").content("{\"message\":\"hello\",\"provider\":\"local\",\"useMemory\":true}")).andExpect(status().isBadRequest());
    }
    @Test void modelSettingsRequireAuthAndNeverReturnKeys() throws Exception {
        mvc.perform(get("/api/model-settings")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/model-settings")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/model-settings/test")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/model-settings")).andExpect(status().isUnauthorized());
        var headers = new org.springframework.http.HttpHeaders(); headers.setBearerAuth("test-only-token"); headers.set("X-Workspace-Key", "e".repeat(64));
        mvc.perform(put("/api/model-settings").headers(headers).contentType("application/json")
            .content("{\"baseUrl\":\"https://api.openai.com/v1\",\"model\":\"mine\",\"apiKey\":\"private-test-value\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.custom").value(true)).andExpect(jsonPath("$.apiKey").doesNotExist())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-test-value"))));
        mvc.perform(get("/api/model-settings").headers(headers)).andExpect(jsonPath("$.model").value("mine"))
            .andExpect(jsonPath("$.encryptedKey").doesNotExist()).andExpect(jsonPath("$.apiKey").doesNotExist());
        mvc.perform(get("/api/workspace").headers(headers)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-test-value"))));
        headers.set("X-Workspace-Key", "f".repeat(64));
        mvc.perform(get("/api/model-settings").headers(headers)).andExpect(jsonPath("$.custom").value(false));
        headers.set("X-Workspace-Key", "e".repeat(64));
        mvc.perform(delete("/api/model-settings").headers(headers)).andExpect(status().isOk()).andExpect(jsonPath("$.custom").value(false));
    }
}
