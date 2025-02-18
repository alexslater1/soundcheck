package app.soundcheck.identity.internal.auth;

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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
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
 * {@code ROLE_<role>} so {@code @PreAuthorize("hasRole('ADMIN')")} works.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    @Bean
    JwtKeys jwtKeys(AuthProperties properties) {
        AuthProperties.Jwt jwt = properties.jwt();
        if (jwt.privateKeyPem() != null && !jwt.privateKeyPem().isBlank()) {
            return JwtKeys.fromPem(jwt.privateKeyPem());
        }
        if (jwt.privateKeyPath() != null && !jwt.privateKeyPath().isBlank()) {
            Path path = Path.of(jwt.privateKeyPath());
            if (path.toFile().isFile()) {
                return JwtKeys.fromFile(path);
            }
            log.warn("JWT key file {} not found — using an ephemeral key pair (run `make keys`)", path);
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
        processor.setJWTClaimsSetVerifier((claims, context) -> {}); // Spring's validators below do the checks
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        OAuth2TokenValidator<Jwt> audience =
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(settings.audience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(settings.issuer()), audience));
        return decoder;
    }

    @Bean
    Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString(JwtIssuer.ROLE_CLAIM);
            return role == null ? List.of() : List.of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }
}
