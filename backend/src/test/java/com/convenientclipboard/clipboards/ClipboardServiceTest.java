package com.convenientclipboard.clipboards;

import com.convenientclipboard.common.ApiException;
import com.convenientclipboard.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClipboardServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-09T01:00:00Z");
    private static final String ID = "a".repeat(32);
    StringRedisTemplate redis;
    ValueOperations<String, String> values;
    ObjectMapper json;
    Clock clock;
    ClipboardService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(), any())).thenReturn(1L);
        json = new ObjectMapper().findAndRegisterModules();
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        AppProperties properties = mock(AppProperties.class);
        when(properties.publicBaseUrl()).thenReturn("https://files.example///");
        service = new ClipboardService(redis, json, properties, clock);
    }

    @Test
    void savesExactTextWithRandomPublicLinkAndAtomicTenMinuteExpiry() throws Exception {
        String content = "  中文 😀 <script>alert('x')</script>\r\n\tsecond line  ";
        ClipboardInfo first = service.create(content);
        ClipboardInfo second = service.create(content);
        assertNotEquals(first.id(), second.id());
        assertTrue(first.id().matches("[A-Za-z0-9_-]{32}"));
        assertEquals(content, first.content());
        assertEquals("https://files.example/clipboard/" + first.id(), first.shareUrl());
        assertEquals(NOW, first.createdAt());
        assertEquals(NOW.plusSeconds(600), first.expiresAt());
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(redis).execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(java.util.List.of("clipboard:content:" + first.id())),
                body.capture(), eq(Long.toString(NOW.plusSeconds(600).toEpochMilli())));
        assertEquals(first, json.readValue(body.getValue(), ClipboardInfo.class));
    }

    @Test
    void readsDoNotRefreshTheDeadlineAndExpiryIsEnforcedAtExactlyTenMinutes() throws Exception {
        ClipboardInfo info = new ClipboardInfo(ID, "text", "https://files.example/clipboard/" + ID, NOW, NOW.plusSeconds(600));
        when(values.get("clipboard:content:" + ID)).thenReturn(json.writeValueAsString(info));
        when(clock.instant()).thenReturn(NOW.plusSeconds(599));
        assertEquals(info, service.get(ID));
        verify(redis, never()).expire(anyString(), any(Duration.class));
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        when(clock.instant()).thenReturn(NOW.plusSeconds(600));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> service.get(ID)).status());
        verify(redis, never()).delete(anyString());
    }

    @Test
    void missingAndInvalidIdentifiersReturnTheSameNotFoundResponse() {
        ApiException missing = assertThrows(ApiException.class, () -> service.get(ID));
        ApiException malformed = assertThrows(ApiException.class, () -> service.get("../invalid"));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
        assertEquals(missing.getMessage(), malformed.getMessage());
        verify(values, times(1)).get(anyString());
    }

    @Test
    void emptyAndOversizedContentNeverReachesStorageAndTheLimitIsInclusive() {
        for (String content : new String[] {null, "", " \r\n\t", "a".repeat(ClipboardService.MAX_CONTENT_LENGTH + 1)}) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class, () -> service.create(content)).status());
        }
        verifyNoInteractions(redis, values);
        assertEquals(ClipboardService.MAX_CONTENT_LENGTH, service.create("中".repeat(ClipboardService.MAX_CONTENT_LENGTH)).content().length());
    }

    @Test
    void redisFailureCannotProduceAnUnstoredSuccessfulShare() {
        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("offline"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ApiException.class, () -> service.create("text")).status());
        when(values.get(anyString())).thenThrow(new DataAccessResourceFailureException("offline"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ApiException.class, () -> service.get(ID)).status());
    }

    @Test
    void aTokenCollisionDoesNotOverwriteAnotherClipboard() {
        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(), any())).thenReturn(0L, 1L);
        ClipboardInfo info = service.create("text");
        assertNotNull(info);
        verify(redis, times(2)).execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(), any());
        verify(values, never()).set(anyString(), anyString());
    }

    @Test
    void customIdsAreTrimmedAndCaseIsPreserved() {
        ClipboardInfo info = service.create("text", "  Release_2026-v1  ");
        assertEquals("Release_2026-v1", info.id());
        assertEquals("https://files.example/clipboard/Release_2026-v1", info.shareUrl());
        assertEquals("Abc", service.create("text", "Abc").id());
        assertEquals("abc", service.create("text", "abc").id());
        assertEquals(64, service.create("text", "a".repeat(64)).id().length());
    }

    @Test
    void omittedEmptyAndWhitespaceCustomIdsKeepTheRandomLinkBehavior() {
        for (String customId : new String[] {null, "", " \t\r\n"}) {
            assertTrue(service.create("text", customId).id().matches("[A-Za-z0-9_-]{32}"));
        }
    }

    @Test
    void invalidCustomIdsAreRejectedBeforeAnyRedisAccess() {
        for (String customId : new String[] {"ab", "a".repeat(65), "_abc", "-abc", "中文名", "a b", "a/b", "a.b", "a?b", "a#b", "a%b"}) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class, () -> service.create("text", customId)).status(), customId);
        }
        verifyNoInteractions(redis, values);
    }

    @Test
    void duplicateCustomIdReturnsConflictWithoutRetryingOrChangingTheExistingExpiry() {
        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(), any())).thenReturn(0L);
        ApiException conflict = assertThrows(ApiException.class, () -> service.create("replacement", "shared-name"));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertTrue(conflict.getMessage().contains("请换一个"));
        verify(redis, times(1)).execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(java.util.List.of("clipboard:content:shared-name")), any(), any());
        verifyNoMoreInteractions(redis);
        verifyNoInteractions(values);
    }

    @Test
    void aNullRedisScriptResultIsUnavailableRatherThanAConflict() {
        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(), any())).thenReturn(null);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ApiException.class, () -> service.create("text", "shared-name")).status());
    }

    @Test
    void customAndLegacyRandomIdsRemainReadableIncludingLegacyLeadingSymbols() throws Exception {
        for (String id : new String[] {"abc", "Release_2026-v1", "a".repeat(64), "_" + "a".repeat(31), "-" + "a".repeat(31)}) {
            ClipboardInfo info = new ClipboardInfo(id, "text", "https://files.example/clipboard/" + id, NOW, NOW.plusSeconds(600));
            when(values.get("clipboard:content:" + id)).thenReturn(json.writeValueAsString(info));
            assertEquals(info, service.get(id));
        }
    }
}
