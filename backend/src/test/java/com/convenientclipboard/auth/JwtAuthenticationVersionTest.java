package com.convenientclipboard.auth;

import com.convenientclipboard.config.AppProperties;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JwtAuthenticationVersionTest {
    UserRepository users = mock(UserRepository.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    UUID id = UUID.randomUUID();
    AppPrincipal principal = new AppPrincipal(id, "tester", "USER");
    JwtService jwt;
    JwtAuthenticationFilter filter;

    @BeforeEach void setUp() {
        jwt = new JwtService(new AppProperties("http://localhost:8088", Path.of("data"), 1024,
                "test-signing-key-at-least-32-characters", "admin", "admin-password-123", "test-encryption-key-at-least-32-characters"));
        filter = new JwtAuthenticationFilter(jwt, users, redis, new AuthCookies(false));
        when(redis.hasKey(anyString())).thenReturn(false);
    }
    @AfterEach void cleanContext() { SecurityContextHolder.clearContext(); }
    private MockHttpServletResponse authenticate(String token, boolean enabled, long currentVersion) throws Exception {
        when(users.byId(id)).thenReturn(Optional.of(new UserRepository.User(id, "tester", "hash", "USER", enabled, currentVersion)));
        var request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.setCookies(new Cookie(AuthCookies.NAME, token));
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        return response;
    }
    @Test void passwordResetInvalidatesAnAlreadyIssuedSignedJwt() throws Exception {
        var response = authenticate(jwt.issue(principal, 0), true, 1);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getCookie(AuthCookies.NAME).getMaxAge()).isZero();
    }
    @Test void disableThenReenableDoesNotReviveOldJwt() throws Exception {
        authenticate(jwt.issue(principal, 3), true, 5);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    @Test void newJwtWithCurrentVersionAuthenticates() throws Exception {
        authenticate(jwt.issue(principal, 5), true, 5);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(principal);
    }
    @Test void disabledAccountCannotAuthenticateEvenWithCurrentVersion() throws Exception {
        authenticate(jwt.issue(principal, 5), false, 5);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    @Test void legacyJwtWithoutVersionIsVersionZeroAndRevocable() throws Exception {
        var legacy = Jwt.withTokenValue("legacy").header("alg", "HS256").subject(id.toString()).claim("jti", "legacy-id")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        JwtService decoder = mock(JwtService.class);
        when(decoder.decode("legacy")).thenReturn(legacy);
        filter = new JwtAuthenticationFilter(decoder, users, redis, new AuthCookies(false));
        authenticate("legacy", true, 0);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        SecurityContextHolder.clearContext();
        authenticate("legacy", true, 1);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
