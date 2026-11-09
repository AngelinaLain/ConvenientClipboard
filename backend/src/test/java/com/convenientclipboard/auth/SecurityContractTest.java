package com.convenientclipboard.auth;

import com.convenientclipboard.config.AppProperties;
import com.convenientclipboard.config.SecurityConfig;
import com.convenientclipboard.storage.SettingsService;
import com.convenientclipboard.storage.StorageSettings;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.*;

@WebMvcTest(controllers = {AuthController.class, SecurityContractTest.ProbeController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, AuthCookies.class, SecurityContractTest.ProbeController.class})
class SecurityContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean UserRepository users;
    @MockitoBean JwtService jwt;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean RateLimiter limiter;
    @MockitoBean AppProperties properties;
    @MockitoBean SettingsService settings;

    @RestController
    static class ProbeController {
        @PostMapping("/api/files") Map<String,Boolean> upload() { return Map.of("ok", true); }
        @GetMapping("/api/admin/probe") Map<String,Boolean> admin() { return Map.of("ok", true); }
        @PostMapping("/api/uploads") Map<String,Boolean> createUpload() { return Map.of("ok", true); }
        @GetMapping("/api/uploads/{id}") Map<String,Boolean> uploadStatus() { return Map.of("ok", true); }
        @PutMapping("/api/uploads/{id}/chunks/{index}") Map<String,Boolean> uploadChunk() { return Map.of("ok", true); }
        @DeleteMapping("/api/uploads/{id}") Map<String,Boolean> cancelUpload() { return Map.of("ok", true); }
        @PostMapping("/api/uploads/{id}/complete") Map<String,Boolean> completeUpload() { return Map.of("ok", true); }
    }
    @Test void anonymousCanGetCsrfToken() throws Exception {
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty()).andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"));
    }
    @Test void publicConfigurationReflectsCurrentDatabaseUploadLimit() throws Exception {
        when(settings.get()).thenReturn(new StorageSettings(10737418240L,50,1048576L));
        mvc.perform(get("/api/public/config")).andExpect(status().isOk()).andExpect(jsonPath("$.maxUploadBytes").value(1048576));
        when(settings.get()).thenReturn(new StorageSettings(10737418240L,50,2097152L));
        mvc.perform(get("/api/public/config")).andExpect(status().isOk()).andExpect(jsonPath("$.maxUploadBytes").value(2097152));
    }
    @Test void publicConfigurationReflectsBothAccessSwitches() throws Exception {
        when(settings.get()).thenReturn(new StorageSettings(10737418240L,50,1048576L,false,true));
        mvc.perform(get("/api/public/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.allowAnonymousUploads").value(false)).andExpect(jsonPath("$.registrationEnabled").value(true));
        when(settings.get()).thenReturn(new StorageSettings(10737418240L,50,1048576L,true,false));
        mvc.perform(get("/api/public/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.allowAnonymousUploads").value(true)).andExpect(jsonPath("$.registrationEnabled").value(false));
    }
    @Test void disabledRegistrationRejectsValidRequestsBeforeUserCreationAndRateLimiting() throws Exception {
        when(settings.get()).thenReturn(new StorageSettings(10737418240L,50,1048576L,true,false));
        mvc.perform(postWithCsrf("/api/auth/register").contentType("application/json")
                        .content("{\"username\":\"new_user\",\"password\":\"password123\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("当前未开放注册，请联系管理员创建账号"));
        verifyNoInteractions(users,limiter);
    }
    @Test void reEnablingRegistrationAllowsCreatingAnOrdinaryUserAndSigningIn() throws Exception {
        var registered=new UserRepository.User(java.util.UUID.randomUUID(),"new_user","hash","USER",true,0);
        when(settings.get()).thenReturn(new StorageSettings(10737418240L,50,1048576L,true,true));
        when(users.create(eq("new_user"),anyString(),eq("USER"))).thenReturn(registered);
        when(jwt.issue(registered.principal(),0)).thenReturn("signed-token");
        mvc.perform(postWithCsrf("/api/auth/register").contentType("application/json")
                        .content("{\"username\":\"NEW_USER\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.username").value("new_user"))
                .andExpect(jsonPath("$.role").value("USER")).andExpect(cookie().value(AuthCookies.NAME,"signed-token"));
    }
    @Test void stateChangesRequireCsrfEvenForAnonymousUpload() throws Exception {
        mvc.perform(post("/api/files")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(postWithCsrf("/api/files")).andExpect(status().isOk());
    }
    @Test void resumableUploadRoutesAllowAnonymousAccessButRequireCsrfForEveryMutation() throws Exception {
        mvc.perform(get("/api/uploads/task")).andExpect(status().isOk());
        for(var request : java.util.List.of(post("/api/uploads"),put("/api/uploads/task/chunks/0"),
                delete("/api/uploads/task"),post("/api/uploads/task/complete"))) {
            mvc.perform(request).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
            var response=mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
            var token=json.readTree(response.getContentAsString());
            mvc.perform(request.cookie(response.getCookies()).header(token.get("headerName").asText(),token.get("token").asText()))
                    .andExpect(status().isOk());
        }
    }
    @Test void meRequiresAuthenticationAndAdministrationRequiresAdminRole() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/probe").with(user("tester").roles("USER"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/admin/probe").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
    }
    @Test void invalidCredentialsAreRejectedBeforeRepositoryAccess() throws Exception {
        mvc.perform(postWithCsrf("/api/auth/register").contentType("application/json")
                .content("{\"username\":null,\"password\":null}"))
                .andExpect(status().isBadRequest());
    }
    @Test void staleCsrfHeaderIsDistinguishedAndFreshTokenWorksWithoutReload() throws Exception {
        var first = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
        var second = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
        var oldToken = json.readTree(first.getContentAsString());
        var newToken = json.readTree(second.getContentAsString());
        mvc.perform(post("/api/files").cookie(second.getCookies())
                        .header(oldToken.get("headerName").asText(), oldToken.get("token").asText()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post("/api/files").cookie(second.getCookies())
                        .header(newToken.get("headerName").asText(), newToken.get("token").asText()))
                .andExpect(status().isOk());
    }
    private MockHttpServletRequestBuilder postWithCsrf(String path) throws Exception {
        var response = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
        var csrf = json.readTree(response.getContentAsString());
        return post(path).cookie(response.getCookies()).header(csrf.get("headerName").asText(), csrf.get("token").asText());
    }
}
