package com.convenientclipboard.admin;

import com.convenientclipboard.auth.AuthCookies;
import com.convenientclipboard.auth.JwtAuthenticationFilter;
import com.convenientclipboard.auth.JwtService;
import com.convenientclipboard.auth.UserRepository;
import com.convenientclipboard.common.ApiException;
import com.convenientclipboard.config.SecurityConfig;
import com.convenientclipboard.storage.StorageSpeedTestService;
import com.convenientclipboard.storage.StorageSpeedTestService.SpeedTest;
import com.convenientclipboard.storage.StorageSpeedTestService.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StorageSpeedTestController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, AuthCookies.class})
class StorageSpeedTestControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean StorageSpeedTestService tests;
    @MockitoBean UserRepository users;
    @MockitoBean JwtService jwt;
    @MockitoBean StringRedisTemplate redis;
    final UUID providerId = UUID.randomUUID();
    final UUID testId = UUID.randomUUID();
    final String path = "/api/admin/providers/" + providerId + "/speed-test";

    private SpeedTest preparing() {
        return new SpeedTest(testId, providerId, Status.PREPARING, StorageSpeedTestService.SAMPLE_BYTES,
                null, null, null, null, Instant.parse("2026-11-09T00:00:00Z"), null, null, null);
    }

    @Test void administratorStartsBackgroundTaskAndReadsStatus() throws Exception {
        when(tests.start(providerId)).thenReturn(preparing());
        when(tests.get(providerId, testId)).thenReturn(preparing());
        mvc.perform(post(path).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(testId.toString()))
                .andExpect(jsonPath("$.providerId").value(providerId.toString()))
                .andExpect(jsonPath("$.status").value("PREPARING"))
                .andExpect(jsonPath("$.sampleBytes").value(16777216))
                .andExpect(jsonPath("$.uploadBytesPerSecond").isEmpty())
                .andExpect(jsonPath("$.downloadBytesPerSecond").isEmpty());
        mvc.perform(get(path + "/" + testId).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PREPARING"));
        verify(tests).start(providerId);
        verify(tests).get(providerId, testId);
    }

    @Test void anonymousAndOrdinaryUsersCannotStartOrReadTests() throws Exception {
        mvc.perform(post(path).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(get(path + "/" + testId)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(user("user").roles("USER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get(path + "/" + testId).with(user("user").roles("USER"))).andExpect(status().isForbidden());
        verifyNoInteractions(tests);
    }

    @Test void evenAdministratorMustProvideCsrfForStartingTest() throws Exception {
        mvc.perform(post(path).with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(tests);
    }

    @Test void concurrentAndUnknownTestsReturnClearClientErrors() throws Exception {
        when(tests.start(providerId)).thenThrow(new ApiException(HttpStatus.CONFLICT, "已有远端存储测速正在进行"));
        when(tests.get(providerId, testId)).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "测速任务不存在"));
        mvc.perform(post(path).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isConflict());
        mvc.perform(get(path + "/" + testId).with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/providers/invalid/speed-test").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isBadRequest());
    }
}
