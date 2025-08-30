package app.soundcheck.notifications.internal.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
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
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
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
