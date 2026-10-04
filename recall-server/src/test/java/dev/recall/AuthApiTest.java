package dev.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.nio.file.Files;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"recall.access-token=legacy-token", "recall.model-key=site-secret-not-for-accounts"})
@AutoConfigureMockMvc
class AuthApiTest {
    @Autowired MockMvc mvc;
    @Autowired Accounts accounts;
    @Autowired ObjectMapper mapper;
    @DynamicPropertySource static void data(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("recall-auth-test-").toString(); r.add("recall.data-dir", () -> dir);
    }
    String credentials(String name) throws Exception { return mapper.writeValueAsString(Map.of("username", name, "password", "testing-password-123")); }
    Cookie cookie(MvcResult result) {
        String header = result.getResponse().getHeader("Set-Cookie");
        assertTrue(header.contains("HttpOnly")); assertTrue(header.contains("SameSite=Strict")); assertTrue(header.contains("Path=/api"));
        return new Cookie("recall_session", header.split(";", 2)[0].split("=",2)[1]);
    }
    Cookie register(String name) throws Exception {
        return cookie(mvc.perform(post("/api/auth/register").header("X-Recall-Client", "web").contentType("application/json").content(credentials(name)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(name)).andExpect(jsonPath("$.workspace").doesNotExist()).andReturn());
    }
    @Test void accountDataAndModelPersistAcrossDevicesAndIgnoreForgedWorkspace() throws Exception {
        Cookie alice = register("alice_api"), bob = register("bob_api");
        mvc.perform(post("/api/memories").cookie(alice).header("X-Recall-Client", "web").contentType("application/json")
            .content("{\"title\":\"private\",\"content\":\"only Alice\",\"kind\":\"fact\"}")).andExpect(status().isOk());
        mvc.perform(put("/api/model-settings").cookie(alice).header("X-Recall-Client", "web").contentType("application/json")
            .content("{\"baseUrl\":\"https://api.openai.com/v1\",\"model\":\"alice-model\",\"apiKey\":\"alice-api-key-test\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.apiKey").doesNotExist());
        Cookie device2 = cookie(mvc.perform(post("/api/auth/login").header("X-Recall-Client", "web").contentType("application/json").content(credentials("alice_api")))
            .andExpect(status().isOk()).andReturn());
        mvc.perform(get("/api/workspace").cookie(device2)).andExpect(jsonPath("$.memories[0].content").value("only Alice"));
        mvc.perform(get("/api/model-settings").cookie(device2)).andExpect(jsonPath("$.model").value("alice-model"));
        String aliceWorkspace = accounts.authenticate(alice.getValue()).workspace();
        mvc.perform(get("/api/workspace").cookie(bob).header("X-Workspace-Key", aliceWorkspace)).andExpect(jsonPath("$.memories").isEmpty());
        mvc.perform(get("/api/model-settings").cookie(bob)).andExpect(jsonPath("$.keyConfigured").value(false));
        mvc.perform(post("/api/chat").cookie(bob).header("X-Recall-Client", "web").contentType("application/json")
            .content("{\"message\":\"hi\",\"provider\":\"local\",\"useMemory\":true}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/workspace").header("Authorization", "Bearer legacy-token").header("X-Workspace-Key", aliceWorkspace)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/chat").cookie(alice).header("X-Recall-Client", "web").contentType("application/json")
            .content("{\"message\":\"hi\",\"provider\":\"dify\",\"useMemory\":true}")).andExpect(status().isBadRequest());
    }
    @Test void enforcesCsrfAndInvalidatesLogoutSession() throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json").content(credentials("csrf_user"))).andExpect(status().isForbidden());
        Cookie session = register("csrf_user");
        mvc.perform(delete("/api/chat").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").cookie(session).header("X-Recall-Client", "web")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/workspace").cookie(session)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").header("X-Recall-Client", "web").contentType("application/json")
            .content("{\"username\":\"csrf_user\",\"password\":\"wrong\"}")).andExpect(status().isUnauthorized());
    }
    @Test void bindsLegacyWorkspaceOnceAndRetainsExistingData() throws Exception {
        String workspace = "9".repeat(64);
        mvc.perform(post("/api/memories").header("Authorization", "Bearer legacy-token").header("X-Workspace-Key", workspace)
            .contentType("application/json").content("{\"title\":\"legacy\",\"content\":\"keep me\",\"kind\":\"fact\"}")).andExpect(status().isOk());
        String body = mapper.writeValueAsString(Map.of("username", "migrated_user", "password", "testing-password-123", "legacyWorkspace", workspace, "legacyToken", "legacy-token"));
        Cookie session = cookie(mvc.perform(post("/api/auth/register").header("X-Recall-Client", "web").contentType("application/json").content(body))
            .andExpect(status().isOk()).andReturn());
        mvc.perform(get("/api/workspace").cookie(session)).andExpect(jsonPath("$.memories[0].content").value("keep me"));
        mvc.perform(get("/api/workspace").header("Authorization", "Bearer legacy-token").header("X-Workspace-Key", workspace)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/register").header("X-Recall-Client", "web").contentType("application/json").content(body.replace("migrated_user", "other_user")))
            .andExpect(status().isBadRequest());
    }
}
