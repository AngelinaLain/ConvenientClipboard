package com.convenientclipboard.auth;

import com.convenientclipboard.config.AppProperties;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.assertj.core.api.Assertions.*;

class JwtServiceTest {
    private JwtService service(String secret) {
        return new JwtService(new AppProperties("http://localhost:8088", Path.of("data"), 1024,
                secret, "admin", "test-password-123", "encryption-key-at-least-32-characters"));
    }
    @Test void issuedTokenHasBoundedLifetimeAndUniqueRevocationIdentity() {
        var service = service("test-signing-key-at-least-32-characters");
        var principal = new AppPrincipal(UUID.randomUUID(), "tester", "USER");
        var first = service.decode(service.issue(principal, 7));
        var second = service.decode(service.issue(principal, 7));
        assertThat(first.getSubject()).isEqualTo(principal.id().toString());
        assertThat(first.getExpiresAt()).isEqualTo(first.getIssuedAt().plus(JwtService.LIFETIME));
        assertThat(first.getId()).isNotBlank().isNotEqualTo(second.getId());
        assertThat(((Number)first.getClaim("ver")).longValue()).isEqualTo(7);
    }
    @Test void rejectsTokenSignedWithAnotherKey() {
        var principal = new AppPrincipal(UUID.randomUUID(), "tester", "ADMIN");
        var forged = service("other-signing-key-at-least-32-characters").issue(principal, 0);
        assertThatThrownBy(() -> service("test-signing-key-at-least-32-characters").decode(forged))
                .isInstanceOf(JwtException.class);
    }
}
