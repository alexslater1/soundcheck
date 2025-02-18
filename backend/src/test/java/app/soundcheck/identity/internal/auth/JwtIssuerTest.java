package app.soundcheck.identity.internal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** Issuing/validating, expiry, wrong key, wrong audience. */
class JwtIssuerTest {

    /** "Now" truncated to seconds (JWT dates have second precision), so the decoder's real clock accepts it. */
    static final Instant T0 = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

    static final AuthProperties.Jwt SETTINGS = new AuthProperties.Jwt(
            null, null, "soundcheck", "soundcheck-app", Duration.ofMinutes(15), Duration.ofDays(30));

    final JwtKeys keys = JwtKeys.generate();
    final UUID userId = UUID.randomUUID();

    @Test
    void issuesTokensTheDecoderAcceptsWithAllClaims() {
        JwtIssuer issuer = new JwtIssuer(keys, SETTINGS, Clock.fixed(T0, ZoneOffset.UTC));
        JwtIssuer.IssuedToken issued = issuer.issue(userId, "ADMIN");

        Jwt jwt = JwtConfig.decoder(keys, SETTINGS).decode(issued.token());

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("soundcheck"); // getIssuer() insists on a URL
        assertThat(jwt.getAudience()).isEqualTo(List.of("soundcheck-app"));
        assertThat(jwt.getIssuedAt()).isEqualTo(T0);
        assertThat(jwt.getExpiresAt())
                .isEqualTo(T0.plus(Duration.ofMinutes(15)))
                .isEqualTo(issued.expiresAt());
        assertThat(jwt.getId()).isEqualTo(issued.jti()).isNotBlank();
        assertThat(jwt.getHeaders()).containsEntry("alg", "ES256").containsEntry("kid", keys.kid());
    }

    @Test
    void expiredTokensAreRejected() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofMinutes(20)), ZoneOffset.UTC);
        String token = new JwtIssuer(keys, SETTINGS, past).issue(userId, "USER").token();

        assertThatThrownBy(() -> JwtConfig.decoder(keys, SETTINGS).decode(token))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void tokensSignedWithAnotherKeyAreRejected() {
        String token = new JwtIssuer(JwtKeys.generate(), SETTINGS, Clock.systemUTC())
                .issue(userId, "USER")
                .token();

        assertThatThrownBy(() -> JwtConfig.decoder(keys, SETTINGS).decode(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void tokensForAnotherAudienceOrIssuerAreRejected() {
        AuthProperties.Jwt other = new AuthProperties.Jwt(
                null, null, "soundcheck", "another-app", Duration.ofMinutes(15), Duration.ofDays(30));
        String token = new JwtIssuer(keys, other, Clock.systemUTC())
                .issue(userId, "USER")
                .token();
        JwtDecoder decoder = JwtConfig.decoder(keys, SETTINGS);
        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("aud");

        AuthProperties.Jwt otherIssuer = new AuthProperties.Jwt(
                null, null, "someone-else", "soundcheck-app", Duration.ofMinutes(15), Duration.ofDays(30));
        String token2 = new JwtIssuer(keys, otherIssuer, Clock.systemUTC())
                .issue(userId, "USER")
                .token();
        assertThatThrownBy(() -> decoder.decode(token2))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("iss");
    }

    @Test
    void keysRoundTripThroughPemAndNeverPrintMaterial() {
        JwtKeys parsed = JwtKeys.fromPem(keys.toPem());
        assertThat(parsed.kid()).isEqualTo(keys.kid());
        assertThat(parsed.publicKey().getEncoded()).isEqualTo(keys.publicKey().getEncoded());
        assertThat(parsed.privateKey().getEncoded()).isEqualTo(keys.privateKey().getEncoded());
        assertThat(keys.toString()).doesNotContain("BEGIN").contains(keys.kid());
        assertThatThrownBy(() -> JwtKeys.fromPem("-----BEGIN PUBLIC KEY-----\nAA==\n-----END PUBLIC KEY-----"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
