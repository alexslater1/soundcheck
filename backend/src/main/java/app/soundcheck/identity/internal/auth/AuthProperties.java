package app.soundcheck.identity.internal.auth;

import app.soundcheck.shared.Redacted;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code soundcheck.auth.*}: the ES256 signing key ({@code JWT_PRIVATE_KEY_PEM} or
 * {@code JWT_PRIVATE_KEY_PATH}) and token lifetimes.
 */
@ConfigurationProperties(prefix = "soundcheck.auth")
public record AuthProperties(Jwt jwt) {

    public record Jwt(
            /** PEM text with a PRIVATE KEY (PKCS#8) and a PUBLIC KEY block; wins over the path when set. */
            String privateKeyPem,
            /** File with the same content, written by {@code make keys}. */
            String privateKeyPath,
            @DefaultValue("soundcheck") String issuer,
            @DefaultValue("soundcheck-app") String audience,
            @DefaultValue("15m") Duration accessTtl,
            @DefaultValue("30d") Duration refreshTtl,
            /**
             * How long after a rotation the replaced token may be presented again, when the token that
             * replaced it has never been presented: the answer was lost on the way.
             */
            @DefaultValue("60s") Duration refreshGrace) {

        /** Never prints the private key. */
        @Override
        public String toString() {
            return "Jwt[privateKeyPem=" + Redacted.of(privateKeyPem) + ", privateKeyPath=" + privateKeyPath
                    + ", issuer=" + issuer + ", audience=" + audience + ", accessTtl=" + accessTtl
                    + ", refreshTtl=" + refreshTtl + ", refreshGrace=" + refreshGrace + "]";
        }
    }
}
