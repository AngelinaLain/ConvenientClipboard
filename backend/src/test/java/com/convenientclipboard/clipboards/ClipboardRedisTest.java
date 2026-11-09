package com.convenientclipboard.clipboards;

import com.convenientclipboard.common.ApiException;
import com.convenientclipboard.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit Redis opt-in; every test only touches a private randomly named clipboard key. */
@EnabledIfEnvironmentVariable(named = "TEST_REDIS_HOST", matches = ".+")
class ClipboardRedisTest {
    private static final DefaultRedisScript<Long> EXPIRY = new DefaultRedisScript<>(
            "return redis.call('PEXPIRETIME', KEYS[1])", Long.class);
    LettuceConnectionFactory connection;
    StringRedisTemplate redis;
    MutableClock clock;
    ClipboardService service;
    String id;
    String key;

    @BeforeEach
    void setup() {
        var configuration = new RedisStandaloneConfiguration(System.getenv("TEST_REDIS_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("TEST_REDIS_PORT", "6379")));
        String password = System.getenv("TEST_REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) configuration.setPassword(password);
        connection = new LettuceConnectionFactory(configuration);
        connection.afterPropertiesSet();
        connection.start();
        redis = new StringRedisTemplate(connection);
        clock = new MutableClock(Instant.now());
        AppProperties properties = mock(AppProperties.class);
        when(properties.publicBaseUrl()).thenReturn("https://files.example");
        service = new ClipboardService(redis, new ObjectMapper().findAndRegisterModules(), properties, clock);
        id = "redis-test-" + UUID.randomUUID();
        key = "clipboard:content:" + id;
    }

    @AfterEach
    void cleanup() {
        try {
            if (redis != null && key != null) redis.delete(key);
        } finally {
            if (connection != null) connection.destroy();
        }
    }

    @Test
    void concurrentClaimsHaveOneWinnerAndNeverOverwriteOrExtendItsAbsoluteExpiry() throws Exception {
        try (var executor = Executors.newFixedThreadPool(12)) {
            var ready = new CountDownLatch(12);
            var start = new CountDownLatch(1);
            List<Future<Object>> requests = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                String content = "contender " + index;
                requests.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        return service.create(content, id);
                    } catch (ApiException ex) {
                        return ex;
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            ClipboardInfo winner = null;
            int conflicts = 0;
            for (Future<Object> request : requests) {
                Object result = request.get(10, TimeUnit.SECONDS);
                if (result instanceof ClipboardInfo info) {
                    assertNull(winner, "Only one claim may succeed");
                    winner = info;
                } else {
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) result).status());
                    conflicts++;
                }
            }
            assertNotNull(winner);
            assertEquals(11, conflicts);
            assertEquals(winner, service.get(id));
            assertEquals(Duration.ofMinutes(10), Duration.between(winner.createdAt(), winner.expiresAt()));
            assertEquals(winner.expiresAt().toEpochMilli(), expiry());
            long originalExpiry = expiry();
            clock.now = clock.now.plusSeconds(100);
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.create("replacement", id)).status());
            assertEquals(originalExpiry, expiry(), "Conflicts must not renew the winner's lifetime");
            assertEquals(winner.content(), service.get(id).content());
        }
    }

    @Test
    void theSameCustomIdCanBeReusedAfterRedisExpiresIt() throws Exception {
        // An old creation clock puts the real ten-minute absolute deadline about two seconds away.
        clock.now = Instant.now().minusSeconds(598);
        ClipboardInfo old = service.create("old", id);
        assertEquals(old.expiresAt().toEpochMilli(), expiry());
        clock.now = Instant.now();
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.create("too soon", id)).status());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> service.get(id)).status());
        clock.now = Instant.now();
        ClipboardInfo fresh = service.create("fresh", id);
        assertEquals(id, fresh.id());
        assertEquals(fresh, service.get(id));
        assertTrue(fresh.createdAt().isAfter(old.createdAt()));
        assertEquals(fresh.expiresAt().toEpochMilli(), expiry());
    }

    private long expiry() {
        return redis.execute(EXPIRY, List.of(key));
    }

    private static class MutableClock extends Clock {
        volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
