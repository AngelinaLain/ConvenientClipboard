package com.convenientclipboard.auth;

import com.convenientclipboard.common.ApiException;

import java.time.Duration;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {
    private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>(
            "local n = redis.call('INCR', KEYS[1]); if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]); end; return n;", Long.class);
    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void check(String namespace, String address, int limit, Duration window) {
        try {
            var count = redis.execute(COUNT, List.of("rate:" + namespace + ":" + address), String.valueOf(window.toSeconds()));
            if (count == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "限流服务暂时不可用");
            if (count > limit) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后重试");
        } catch (DataAccessException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "认证服务暂时不可用");
        }
    }
}
