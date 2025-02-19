package app.soundcheck.identity.internal.auth;

import app.soundcheck.identity.internal.UserRecord;
import app.soundcheck.identity.internal.UserRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Wires the signing keys, the issuer and the resource-server decoder. The decoder
 * accepts only ES256 tokens from our issuer for our audience; the {@code role} claim becomes
 * {@code ROLE_<role>} so {@code @PreAuthorize("hasRole('ADMIN')")} works. Tokens of deleted accounts are
 * rejected on every endpoint via a short-lived cache of the {@code deleted_at} flag, so deleting
 * an account signs it out at once. Dual-key acceptance during rotation is not implemented yet: {@code kid}
 * is emitted, one key is accepted.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    /** How long a "not deleted" verdict is trusted before re-reading the row. */
    static final Duration DELETED_CHECK_TTL = Duration.ofSeconds(60);

    @Bean
    JwtKeys jwtKeys(AuthProperties properties, Environment environment) {
        AuthProperties.Jwt jwt = properties.jwt();
        if (jwt.privateKeyPem() != null && !jwt.privateKeyPem().isBlank()) {
            return JwtKeys.fromPem(jwt.privateKeyPem());
        }
        boolean production = Arrays.asList(environment.getActiveProfiles()).contains("aws");
        if (jwt.privateKeyPath() != null && !jwt.privateKeyPath().isBlank()) {
            Path path = Path.of(jwt.privateKeyPath());
            if (path.toFile().isFile()) {
                return JwtKeys.fromFile(path);
            }
            if (production) {
                throw new IllegalStateException(
                        "JWT key file " + path + " not found (JWT_PRIVATE_KEY_PEM/PATH required on AWS)");
            }
            log.warn("JWT key file {} not found — using an ephemeral key pair (run `make keys`)", path);
        } else if (production) {
            throw new IllegalStateException("JWT_PRIVATE_KEY_PEM or JWT_PRIVATE_KEY_PATH is required on AWS");
        } else {
            log.warn("No JWT key configured — using an ephemeral key pair (tokens die with this process)");
        }
        return JwtKeys.generate();
    }

    @Bean
    JwtIssuer jwtIssuer(JwtKeys keys, AuthProperties properties, Clock clock) {
        return new JwtIssuer(keys, properties.jwt(), clock);
    }

    @Bean
    JwtDecoder jwtDecoder(JwtKeys keys, AuthProperties properties) {
        return decoder(keys, properties.jwt());
    }

    static JwtDecoder decoder(JwtKeys keys, AuthProperties.Jwt settings) {
        // NimbusJwtDecoder.withPublicKey is RSA-only; for EC we hand Nimbus a JWK set keyed by kid.
        JWKSet jwkSet = new JWKSet(new ECKey.Builder(Curve.P_256, keys.publicKey())
                .keyID(keys.kid())
                .build());
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(
                new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, new ImmutableJWKSet<>(jwkSet)));
        // No-op: Spring's validators below check exp/nbf (60 s skew), issuer and audience.
        processor.setJWTClaimsSetVerifier((claims, context) -> {});
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        // Spring normalises `aud` to a list even when the token carries a single string.
        OAuth2TokenValidator<Jwt> audience =
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(settings.audience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(settings.issuer()), audience));
        return decoder;
    }

    @Bean
    Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter(UserRepository users, Clock clock) {
        JwtAuthenticationConverter roles = new JwtAuthenticationConverter();
        roles.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString(JwtIssuer.ROLE_CLAIM);
            return role == null ? List.of() : List.of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        DeletedUserGuard guard = new DeletedUserGuard(users, clock);
        // A class, not a lambda: Boot 3.4 adds every Converter bean to the MVC conversion service and needs its types.
        return new Converter<Jwt, AbstractAuthenticationToken>() {
            @Override
            public AbstractAuthenticationToken convert(Jwt jwt) {
                if (guard.isDeleted(UUID.fromString(jwt.getSubject()))) {
                    throw new org.springframework.security.oauth2.jwt.BadJwtException("Account deleted");
                }
                return roles.convert(jwt);
            }
        };
    }

    /** Caches "deleted?" per user for {@link #DELETED_CHECK_TTL}; deleted verdicts are cached forever. */
    static final class DeletedUserGuard {

        private record Verdict(boolean deleted, Instant until) {}

        private final UserRepository users;
        private final Clock clock;
        private final Map<UUID, Verdict> cache = new ConcurrentHashMap<>();

        DeletedUserGuard(UserRepository users, Clock clock) {
            this.users = users;
            this.clock = clock;
        }

        boolean isDeleted(UUID userId) {
            Instant now = Instant.now(clock);
            Verdict cached = cache.get(userId);
            if (cached != null && (cached.deleted() || cached.until().isAfter(now))) {
                return cached.deleted();
            }
            boolean deleted = users.findById(userId).map(UserRecord::deleted).orElse(true);
            cache.put(userId, new Verdict(deleted, now.plus(DELETED_CHECK_TTL)));
            if (cache.size() > 50_000) {
                cache.clear(); // crude bound; a Caffeine cache is the upgrade path
            }
            return deleted;
        }
    }
}
