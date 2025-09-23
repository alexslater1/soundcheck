package app.soundcheck.identity.internal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Where the signing key comes from. On AWS the process with the api role must have the real key:
 * it issues and accepts the tokens. The worker and the one-off job never see a token, so they start without
 * one (an ephemeral pair), and their task definitions carry no JWT secret.
 */
class JwtKeySourceTest {

    private static final String MISSING_FILE = "/nowhere/jwt-es256.pem";

    private final JwtConfig config = new JwtConfig();

    @Test
    void theApiOnAwsRefusesToStartWithoutAKey() {
        assertThatThrownBy(() -> config.jwtKeys(properties(null, MISSING_FILE), aws("api,scheduler")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY_PEM");
        assertThatThrownBy(() -> config.jwtKeys(properties(null, ""), aws("api")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY_PEM");
    }

    @Test
    void theWorkerAndTheJobOnAwsStartWithAnEphemeralKey() {
        assertThat(config.jwtKeys(properties(null, MISSING_FILE), aws("worker")))
                .isNotNull();
        assertThat(config.jwtKeys(properties(null, ""), aws("cli"))).isNotNull();
    }

    @Test
    void theApiOnAwsUsesThePemFromItsSecret() {
        JwtKeys secret = JwtKeys.generate();

        JwtKeys used = config.jwtKeys(properties(secret.toPem(), MISSING_FILE), aws("api,scheduler"));

        assertThat(used.kid()).isEqualTo(secret.kid());
    }

    /** `make dev`: no profile, every role, no key file yet (before `make keys`): a warning, not a failure. */
    @Test
    void locallyAMissingKeyIsStillOnlyAWarning() {
        MockEnvironment local = new MockEnvironment();

        assertThat(config.jwtKeys(properties(null, MISSING_FILE), local)).isNotNull();
    }

    private static MockEnvironment aws(String roles) {
        MockEnvironment environment = new MockEnvironment().withProperty("soundcheck.roles", roles);
        environment.setActiveProfiles("aws");
        return environment;
    }

    private static AuthProperties properties(String pem, String path) {
        return new AuthProperties(new AuthProperties.Jwt(
                pem,
                path,
                "soundcheck",
                "soundcheck-app",
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                Duration.ofSeconds(60)));
    }
}
