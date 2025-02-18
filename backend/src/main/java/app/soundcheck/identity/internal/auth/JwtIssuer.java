package app.soundcheck.identity.internal.auth;

import app.soundcheck.shared.Ids;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Issues the short-lived ES256 access tokens: claims {@code sub} (user id), {@code role},
 * {@code iat}, {@code exp}, {@code jti}, {@code iss}, {@code aud}; header {@code kid} for rotation.
 */
public class JwtIssuer {

    public static final String ROLE_CLAIM = "role";

    private final JwtKeys keys;
    private final AuthProperties.Jwt settings;
    private final Clock clock;

    public JwtIssuer(JwtKeys keys, AuthProperties.Jwt settings, Clock clock) {
        this.keys = keys;
        this.settings = settings;
        this.clock = clock;
    }

    public IssuedToken issue(UUID userId, String role) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(settings.accessTtl());
        String jti = Ids.newId().toString();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .claim(ROLE_CLAIM, role)
                .issuer(settings.issuer())
                .audience(settings.audience())
                // Numeric-date claims set directly (no java.util.Date — ArchitectureTests); the decoder reads them as
                // Instants.
                .claim("iat", now.getEpochSecond())
                .claim("exp", expiresAt.getEpochSecond())
                .jwtID(jti)
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keys.kid()).build(), claims);
        try {
            jwt.sign(new ECDSASigner(keys.privateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign access token", e);
        }
        return new IssuedToken(jwt.serialize(), expiresAt, jti);
    }

    /** A signed access token and when it stops being accepted. */
    public record IssuedToken(String token, Instant expiresAt, String jti) {}
}
