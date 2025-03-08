package app.soundcheck.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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

    private final TestRestTemplate rest;

    public TestApi(TestRestTemplate rest) {
        this.rest = rest;
    }

    public User register(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "-" + suffix + "@example.com";
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
