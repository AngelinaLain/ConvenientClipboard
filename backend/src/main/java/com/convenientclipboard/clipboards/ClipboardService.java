package com.convenientclipboard.clipboards;

import com.convenientclipboard.common.ApiException;
import com.convenientclipboard.config.AppProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ClipboardService {
    public static final int MAX_CONTENT_LENGTH = 100_000;
    static final Duration LIFETIME = Duration.ofMinutes(10);
    private static final String KEY_PREFIX = "clipboard:content:";
    private static final String CUSTOM_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9_-]{2,63}";
    private static final DefaultRedisScript<Long> SAVE = new DefaultRedisScript<>(
            "if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PXAT', ARGV[2]) then return 1 else return 0 end", Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final String publicBaseUrl;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public ClipboardService(StringRedisTemplate redis, ObjectMapper json, AppProperties properties) {
        this(redis, json, properties, Clock.systemUTC());
    }

    ClipboardService(StringRedisTemplate redis, ObjectMapper json, AppProperties properties, Clock clock) {
        this.redis = redis;
        this.json = json;
        this.publicBaseUrl = properties.publicBaseUrl().replaceAll("/+$", "");
        this.clock = clock;
    }

    public ClipboardInfo create(String content) {
        return create(content, null);
    }

    public ClipboardInfo create(String content, String customId) {
        if (content == null || content.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请输入粘贴板内容");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "粘贴板内容不能超过 100000 个字符");
        }
        String requestedId = customId == null ? "" : customId.trim();
        if (!requestedId.isEmpty() && !requestedId.matches(CUSTOM_ID_PATTERN)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "自定义链接需为 3–64 个字符，以英文字母或数字开头，仅支持英文字母、数字、下划线和连字符");
        }
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                String id = requestedId.isEmpty() ? randomId() : requestedId;
                var createdAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
                var info = new ClipboardInfo(id, content, publicBaseUrl + "/clipboard/" + id,
                        createdAt, createdAt.plus(LIFETIME));
                // Claim and absolute expiry are one atomic Redis operation. Conflicts never change the existing TTL.
                Long saved = redis.execute(SAVE, List.of(KEY_PREFIX + id), json.writeValueAsString(info),
                        Long.toString(info.expiresAt().toEpochMilli()));
                if (saved == null) throw unavailable();
                if (saved == 1) return info;
                if (!requestedId.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "此自定义链接已被使用，请换一个；原链接将在 10 分钟内到期");
            }
            throw unavailable();
        } catch (DataAccessException | JsonProcessingException ex) {
            throw unavailable();
        }
    }

    public ClipboardInfo get(String id) {
        if (id == null || !(id.matches(CUSTOM_ID_PATTERN) || id.matches("[A-Za-z0-9_-]{32}"))) throw missing();
        try {
            String key = KEY_PREFIX + id;
            String value = redis.opsForValue().get(key);
            if (value == null) throw missing();
            ClipboardInfo info = json.readValue(value, ClipboardInfo.class);
            // Also check the absolute deadline; reads must never extend the lifetime.
            if (!info.expiresAt().isAfter(clock.instant())) {
                // Redis expires the key atomically. Deleting after GET could remove a newer clipboard reusing this ID.
                throw missing();
            }
            return info;
        } catch (DataAccessException | JsonProcessingException ex) {
            throw unavailable();
        }
    }

    private String randomId() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "粘贴板不存在或已过期");
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "粘贴板服务暂时不可用，请稍后重试");
    }
}
