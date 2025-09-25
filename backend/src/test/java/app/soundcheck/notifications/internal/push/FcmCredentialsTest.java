package app.soundcheck.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.shared.config.JsonMappers;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The key file as Firebase hands it out: a PEM private key inside JSON, with \n escapes. */
class FcmCredentialsTest {

    private final JsonMapper json = JsonMappers.builder().build();

    @Test
    void readsTheProjectTheSignerAndTheKeyButNeverPrintsTheKey() throws Exception {
        FcmCredentials credentials = FcmCredentials.parse(keyJson("soundcheck-test"), json);

        assertThat(credentials.projectId()).isEqualTo("soundcheck-test");
        assertThat(credentials.clientEmail()).isEqualTo("push@soundcheck-test.iam.gserviceaccount.com");
        assertThat(credentials.keyId()).isEqualTo("abc123");
        assertThat(credentials.privateKey().getAlgorithm()).isEqualTo("RSA");
        assertThat(credentials.toString()).doesNotContain("PRIVATE").doesNotContain("abc123");
    }

    /** ECS hands secrets over as environment variables (PUSH_CREDENTIALS_JSON); the text beats the file. */
    @Test
    void theInlineKeyWinsOverTheFile(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("key.json"), keyJson("from-the-file"));

        FcmCredentials inline = FcmCredentials.from(properties(file.toString(), keyJson("from-the-env")), json);
        FcmCredentials fromFile = FcmCredentials.from(properties(file.toString(), ""), json);

        assertThat(inline.projectId()).isEqualTo("from-the-env");
        assertThat(fromFile.projectId()).isEqualTo("from-the-file");
    }

    @Test
    void anInlineKeyNeedsNoFile() throws Exception {
        FcmCredentials inline = FcmCredentials.from(properties("/nowhere/key.json", keyJson("inline-only")), json);

        assertThat(inline.projectId()).isEqualTo("inline-only");
    }

    /** A JSON parser's message can quote the text it stopped at; here that text is a private key. */
    @Test
    void brokenJsonFailsWithoutQuotingIt() {
        String broken = "{\"private_key\": \"-----BEGIN PRIVATE KEY-----\\nMIIEsecretbytes oops";

        assertThatThrownBy(() -> FcmCredentials.from(properties("", broken), json))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause()
                .satisfies(
                        e -> assertThat(e.getMessage()).doesNotContain("MIIE").doesNotContain("PRIVATE"));
    }

    @Test
    void thePropertiesNeverPrintTheInlineKey() throws Exception {
        PushProperties properties = properties("../.secrets/fcm.json", keyJson("soundcheck-test"));

        assertThat(properties.toString())
                .contains("credentialsJson=<redacted>")
                .doesNotContain("PRIVATE")
                .doesNotContain("soundcheck-test");
    }

    private static PushProperties properties(String file, String inline) {
        return new PushProperties(true, file, inline, "https://fcm.googleapis.com", "");
    }

    private static String keyJson(String projectId) throws Exception {
        var keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        // 64-character lines joined by a JSON "\\n" escape, as in a real key file.
        String base64 = Base64.getEncoder()
                .encodeToString(keys.generateKeyPair().getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\\n"
                + String.join("\\n", base64.split("(?<=\\G.{64})"))
                + "\\n-----END PRIVATE KEY-----\\n";
        return """
                {"type": "service_account", "project_id": "%s", "private_key_id": "abc123",
                 "private_key": "%s", "client_email": "push@soundcheck-test.iam.gserviceaccount.com",
                 "token_uri": "https://oauth2.googleapis.com/token"}"""
                .formatted(projectId, pem);
    }
}
