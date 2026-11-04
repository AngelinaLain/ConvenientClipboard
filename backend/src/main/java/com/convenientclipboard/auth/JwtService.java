package com.convenientclipboard.auth;

import com.convenientclipboard.config.AppProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    public static final Duration LIFETIME = Duration.ofHours(8);
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    public JwtService(AppProperties properties) {
        byte[] key = properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        var d = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        d.setJwtValidator(JwtValidators.createDefaultWithIssuer("convenient-clipboard"));
        this.decoder = d;
    }
    public String issue(AppPrincipal principal, long authVersion) {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("convenient-clipboard").issuedAt(now)
                .expiresAt(now.plus(LIFETIME)).subject(principal.id().toString())
                .id(UUID.randomUUID().toString()).claim("ver", authVersion).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
    public Jwt decode(String value) { return decoder.decode(value); }
}
