package app.soundcheck.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.shared.config.JsonMappers;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** The key file as Firebase hands it out: a PEM private key inside JSON, with \n escapes. */
class FcmCredentialsTest {

    @Test
    void readsTheProjectTheSignerAndTheKeyButNeverPrintsTheKey() throws Exception {
        var keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        // 64-character lines joined by a JSON "\\n" escape, as in a real key file.
        String base64 = Base64.getEncoder()
                .encodeToString(keys.generateKeyPair().getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\\n"
                + String.join("\\n", base64.split("(?<=\\G.{64})"))
                + "\\n-----END PRIVATE KEY-----\\n";
        String file =
                """
                {"type": "service_account", "project_id": "soundcheck-test", "private_key_id": "abc123",
                 "private_key": "%s", "client_email": "push@soundcheck-test.iam.gserviceaccount.com",
                 "token_uri": "https://oauth2.googleapis.com/token"}"""
                        .formatted(pem);

        FcmCredentials credentials =
                FcmCredentials.parse(file, JsonMappers.builder().build());

        assertThat(credentials.projectId()).isEqualTo("soundcheck-test");
        assertThat(credentials.clientEmail()).isEqualTo("push@soundcheck-test.iam.gserviceaccount.com");
        assertThat(credentials.keyId()).isEqualTo("abc123");
        assertThat(credentials.privateKey().getAlgorithm()).isEqualTo("RSA");
        assertThat(credentials.toString()).doesNotContain("PRIVATE").doesNotContain("abc123");
    }
}
