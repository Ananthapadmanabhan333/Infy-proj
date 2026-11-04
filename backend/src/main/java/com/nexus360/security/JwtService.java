package com.nexus360.security;

import com.nexus360.config.AppProperties;
import com.nexus360.user.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/** Issues and validates short-lived HS256 access tokens. */
@Component
public class JwtService {

    public static final String ISSUER = "nexus360";
    /** Session id claim (Release 3). */
    public static final String SESSION_CLAIM = "sid";

    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final Duration lifetime;
    private final Clock clock;

    public JwtService(AppProperties properties, Clock clock) {
        SecretKey key = new SecretKeySpec(properties.security().jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        this.lifetime = Duration.ofMinutes(properties.security().accessTokenMinutes());
        this.clock = clock;
    }

    public String issue(User user) {
        return issue(user, null);
    }

    /** {@code sessionId} becomes the {@code sid} claim, so the API knows which session a request belongs to. */
    public String issue(User user, Long sessionId) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(lifetime))
                .subject(String.valueOf(user.getId()))
                .id(UUID.randomUUID().toString())
                .claim("email", user.getEmail())
                .claim("name", user.getFullName())
                .claim("role", user.getRole().name());
        if (sessionId != null) {
            builder.claim(SESSION_CLAIM, sessionId);
        }
        JwtClaimsSet claims = builder.build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public long lifetimeSeconds() {
        return lifetime.toSeconds();
    }

    public JwtDecoder decoder() {
        return decoder;
    }
}
