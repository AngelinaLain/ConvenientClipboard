package com.convenientclipboard.auth;

import com.convenientclipboard.common.ApiException;
import com.convenientclipboard.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final StringRedisTemplate redis;
    private final RateLimiter limiter;
    private final AppProperties properties;
    private final AuthCookies cookies;
    private final String dummyHash;
    public AuthController(UserRepository users, PasswordEncoder passwords, JwtService jwt, StringRedisTemplate redis,
                          RateLimiter limiter, AppProperties properties, AuthCookies cookies) {
        this.users = users; this.passwords = passwords; this.jwt = jwt; this.redis = redis;
        this.limiter = limiter; this.properties = properties;
        this.cookies = cookies;
        this.dummyHash = passwords.encode(java.util.UUID.randomUUID().toString());
    }
    public record Credentials(@NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,32}", message = "使用 3–32 位字母、数字或下划线") String username,
                              @NotBlank @Size(min = 8, max = 72, message = "使用 8–72 位密码") String password) {}
    @GetMapping("/auth/csrf") public Map<String,String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    @GetMapping("/public/config") public Map<String,Object> publicConfig() {
        return Map.of("retentionDays", 7, "maxUploadBytes", properties.maxUploadBytes(), "registrationEnabled", true);
    }
    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AppPrincipal register(@Valid @RequestBody Credentials input, HttpServletRequest req, HttpServletResponse res) {
        limiter.check("register", req.getRemoteAddr(), 10, Duration.ofHours(1));
        PasswordPolicy.validate(input.password());
        try {
            var user = users.create(input.username().toLowerCase(Locale.ROOT), passwords.encode(input.password()), "USER");
            cookies.set(req, res, jwt.issue(user.principal(), user.authVersion()), JwtService.LIFETIME);
            return user.principal();
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "用户名已被使用");
        }
    }
    @PostMapping("/auth/login")
    public AppPrincipal login(@Valid @RequestBody Credentials input, HttpServletRequest req, HttpServletResponse res) {
        limiter.check("login", req.getRemoteAddr(), 30, Duration.ofMinutes(15));
        PasswordPolicy.validate(input.password());
        var user = users.byUsername(input.username().toLowerCase(Locale.ROOT));
        boolean matches = passwords.matches(input.password(), user.map(UserRepository.User::passwordHash).orElse(dummyHash));
        if (!matches || user.isEmpty() || !user.get().enabled())
            throw new ApiException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        var principal = user.get().principal();
        cookies.set(req, res, jwt.issue(principal, user.get().authVersion()), JwtService.LIFETIME);
        return principal;
    }
    @GetMapping("/auth/me") public AppPrincipal me(@AuthenticationPrincipal AppPrincipal principal) { return principal; }
    @PostMapping("/auth/logout")
    public Map<String,Boolean> logout(HttpServletRequest req, HttpServletResponse res) {
        cookies.read(req).ifPresent(token -> {
            var claims = jwt.decode(token);
            var remaining = Duration.between(Instant.now(), claims.getExpiresAt());
            if (!remaining.isNegative() && !remaining.isZero()) {
                try { redis.opsForValue().set("auth:revoked:" + claims.getId(), "1", remaining); }
                catch (DataAccessException ex) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "退出失败，请稍后重试"); }
            }
        });
        cookies.clear(req, res);
        return Map.of("success", true);
    }
}
