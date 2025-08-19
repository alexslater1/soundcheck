package app.soundcheck.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Small HTTP helper for API integration tests: registers users and makes authenticated JSON calls. */
public final class TestApi {

    /** A registered user and their access token. */
    public record User(UUID id, String handle, String email, String accessToken) {}

    /** The bootstrap admin every IT context creates (IntegrationTest's {@code soundcheck.admin.*}). */
    public static final String ADMIN_EMAIL = "admin@test.local";

    public static final String ADMIN_PASSWORD = "admin-test-passphrase";

    /** Access tokens live 15 minutes; one login is reused for 10, so a long run never holds a stale one. */
    private static final Duration ADMIN_TOKEN_REUSE = Duration.ofMinutes(10);

    private static User cachedAdmin;
    private static Instant cachedAdminAt;

    private final TestRestTemplate rest;

    public TestApi(TestRestTemplate rest) {
        this.rest = rest;
    }

    public User register(String prefix) {
        return register(prefix, "example.com");
    }

    /** A user whose email is at {@code domain} (e.g. a synthetic persona's {@code seed.soundcheck.test}). */
    public User register(String prefix, String domain) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "-" + suffix + "@" + domain;
        String handle = prefix + "_" + suffix;
        ResponseEntity<JsonNode> response = call(
                HttpMethod.POST,
                "/api/v1/auth/register",
                Map.of("email", email, "password", "correct horse battery", "handle", handle, "displayName", prefix),
                null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = response.getBody();
        return new User(
                UUID.fromString(body.path("user").path("id").asText("")),
                handle,
                email,
                body.path("accessToken").asText(""));
    }

    /**
     * The bootstrap admin, logged in once and reused: logging in for every test would also run
     * into the login rate limit. Every IT context shares the database and the signing key, so the token is
     * valid whichever context made it.
     */
    public synchronized User admin() {
        if (cachedAdmin == null || Instant.now().isAfter(cachedAdminAt.plus(ADMIN_TOKEN_REUSE))) {
            ResponseEntity<JsonNode> response = call(
                    HttpMethod.POST,
                    "/api/v1/auth/login",
                    Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD),
                    null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode body = response.getBody();
            cachedAdmin = new User(
                    UUID.fromString(body.path("user").path("id").asText("")),
                    body.path("user").path("handle").asText(""),
                    ADMIN_EMAIL,
                    body.path("accessToken").asText(""));
            cachedAdminAt = Instant.now();
        }
        return cachedAdmin;
    }

    public ResponseEntity<JsonNode> get(String path, User user) {
        return call(HttpMethod.GET, path, null, user);
    }

    public ResponseEntity<JsonNode> put(String path, Object body, User user) {
        return call(HttpMethod.PUT, path, body, user);
    }

    public ResponseEntity<JsonNode> post(String path, Object body, User user) {
        return call(HttpMethod.POST, path, body, user);
    }

    public ResponseEntity<JsonNode> patch(String path, Object body, User user) {
        return call(HttpMethod.PATCH, path, body, user);
    }

    public ResponseEntity<JsonNode> delete(String path, User user) {
        return call(HttpMethod.DELETE, path, null, user);
    }

    public ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body, User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Client", "android");
        if (user != null) {
            headers.setBearerAuth(user.accessToken());
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
