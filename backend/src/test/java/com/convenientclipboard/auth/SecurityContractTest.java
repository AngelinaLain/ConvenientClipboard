package com.convenientclipboard.auth;

import com.convenientclipboard.config.AppProperties;
import com.convenientclipboard.config.SecurityConfig;
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

    @RestController
    static class ProbeController {
        @PostMapping("/api/files") Map<String,Boolean> upload() { return Map.of("ok", true); }
        @GetMapping("/api/admin/probe") Map<String,Boolean> admin() { return Map.of("ok", true); }
    }
    @Test void anonymousCanGetCsrfToken() throws Exception {
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty()).andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"));
    }
    @Test void stateChangesRequireCsrfEvenForAnonymousUpload() throws Exception {
        mvc.perform(post("/api/files")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(postWithCsrf("/api/files")).andExpect(status().isOk());
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
