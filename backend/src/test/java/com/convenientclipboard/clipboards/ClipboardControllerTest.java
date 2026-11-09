package com.convenientclipboard.clipboards;

import com.convenientclipboard.auth.AuthCookies;
import com.convenientclipboard.auth.JwtAuthenticationFilter;
import com.convenientclipboard.auth.JwtService;
import com.convenientclipboard.auth.UserRepository;
import com.convenientclipboard.common.ApiException;
import com.convenientclipboard.config.AppProperties;
import com.convenientclipboard.config.SecurityConfig;
import com.convenientclipboard.storage.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ClipboardController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, AuthCookies.class})
class ClipboardControllerTest {
    private static final String ID = "a".repeat(32);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean ClipboardService service;
    @MockitoBean JwtService jwt;
    @MockitoBean UserRepository users;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean AppProperties properties;
    @MockitoBean SettingsService settings;

    @Test
    void anyoneCanCreateAndReadTextWithoutAnAccountOrFileUploadPermission() throws Exception {
        var now = Instant.parse("2026-11-09T01:00:00Z");
        var info = new ClipboardInfo(ID, "<b>原样文本</b>\nline 2", "https://files.example/zt/" + ID, now, now.plusSeconds(600));
        when(service.create(info.content(), null)).thenReturn(info);
        when(service.get(ID)).thenReturn(info);
        mvc.perform(post("/api/clipboards").with(csrf()).contentType("application/json")
                        .content("{\"content\":\"<b>原样文本</b>\\nline 2\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.content").value(info.content())).andExpect(jsonPath("$.shareUrl").value(info.shareUrl()))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-09T01:10:00Z"));
        mvc.perform(get("/api/clipboards/" + ID)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.content").value(info.content()));
        verifyNoInteractions(users, settings);
    }

    @Test
    void anonymousCreationStillRequiresCsrfProtection() throws Exception {
        mvc.perform(post("/api/clipboards").contentType("application/json").content("{\"content\":\"text\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(service);
    }

    @Test
    void anonymousUsersCanCreateCustomLinksAndReceiveValidationAndConflictMessages() throws Exception {
        var now = Instant.parse("2026-11-09T01:00:00Z");
        var info = new ClipboardInfo("Demo-2026", "text", "https://files.example/zt/Demo-2026", now, now.plusSeconds(600));
        when(service.create("text", " Demo-2026 ")).thenReturn(info);
        when(service.get(info.id())).thenReturn(info);
        mvc.perform(post("/api/clipboards").with(csrf()).contentType("application/json")
                        .content(json.writeValueAsString(new ClipboardController.CreateRequest("text", " Demo-2026 "))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(info.id()));
        mvc.perform(get("/api/clipboards/" + info.id())).andExpect(status().isOk());
        when(service.create("text", "Demo-2026")).thenThrow(new ApiException(HttpStatus.CONFLICT, "此自定义链接已被使用，请换一个"));
        mvc.perform(post("/api/clipboards").with(csrf()).contentType("application/json")
                        .content("{\"content\":\"text\",\"customId\":\"Demo-2026\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("此自定义链接已被使用，请换一个"));
        when(service.create("text", "bad/name")).thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "自定义链接格式无效"));
        mvc.perform(post("/api/clipboards").with(csrf()).contentType("application/json")
                        .content("{\"content\":\"text\",\"customId\":\"bad/name\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("自定义链接格式无效"));
        verifyNoInteractions(users, settings);
    }

    @Test
    void expiredLinksGiveAPublicNotFoundResponse() throws Exception {
        when(service.get(anyString())).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "粘贴板不存在或已过期"));
        mvc.perform(get("/api/clipboards/" + ID)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("粘贴板不存在或已过期"));
    }

    @Test
    void oversizedRequestsAreRejectedBeforeDeserializationAndStorage() throws Exception {
        mvc.perform(post("/api/clipboards").with(csrf()).contentType("application/json")
                        .content("{\"content\":\"" + "a".repeat(1024 * 1024) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(service);
    }
}
