package app.soundcheck.notifications.internal.push;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * The parts of a Firebase service account key (the JSON file from Project settings → Service accounts)
 * that sending needs: which project, who signs, and the RSA key it signs with.
 */
record FcmCredentials(String projectId, String clientEmail, String keyId, RSAPrivateKey privateKey, String tokenUri) {

    /**
     * The inline key ({@code PUSH_CREDENTIALS_JSON}: ECS passes secrets as environment variables) when it
     * is set, otherwise the key file ({@code PUSH_CREDENTIALS_FILE}, the local setup).
     */
    static FcmCredentials from(PushProperties properties, JsonMapper json) {
        if (!properties.credentialsJson().isBlank()) {
            return parse(properties.credentialsJson(), json);
        }
        return read(Path.of(properties.credentialsFile()), json);
    }

    static FcmCredentials read(Path file, JsonMapper json) {
        try {
            return parse(Files.readString(file), json);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "soundcheck.push.enabled is true but the key file cannot be read: " + file, e);
        }
    }

    static FcmCredentials parse(String content, JsonMapper json) {
        JsonNode key;
        try {
            key = json.readTree(content);
        } catch (JacksonException e) {
            // Without the cause: a parser's message can quote the text it stopped at, here a private key.
            throw new IllegalStateException("The Firebase key is not valid JSON");
        }
        String pem = key.path("private_key").asText("");
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            RSAPrivateKey privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            return new FcmCredentials(
                    key.path("project_id").asText(""),
                    key.path("client_email").asText(""),
                    key.path("private_key_id").asText(""),
                    privateKey,
                    key.path("token_uri").asText("https://oauth2.googleapis.com/token"));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("The Firebase key file has no usable private_key", e);
        }
    }

    @Override
    public String toString() {
        return "FcmCredentials[projectId=" + projectId + ", clientEmail=" + clientEmail + "]"; // never the key
    }
}
