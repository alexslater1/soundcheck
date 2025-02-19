package app.soundcheck;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Boots the whole application against PostGIS + LocalStack: Flyway migrates V001, the health endpoint
 * answers, readiness sees the database, and protected paths are rejected without a token.
 */
class SmokeIT extends IntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void healthIsUp() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"status\":\"UP\"")
                .contains("\"version\"")
                .contains("\"time\"");
    }

    @Test
    void readinessIncludesTheDatabase() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health/readiness", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"db\":{")
                .contains("\"status\":\"UP\"")
                .doesNotContain("DOWN");
    }

    @Test
    void openApiDocumentIsServed() {
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"operationId\":\"health\"");
    }

    @Test
    void corsAllowsOnlyTheLocalAppOrigins() {
        HttpHeaders allowed = new HttpHeaders();
        allowed.setOrigin("http://localhost:8081");
        allowed.set("Access-Control-Request-Method", "GET");
        allowed.set("Access-Control-Request-Headers", "x-client");
        ResponseEntity<Void> preflight =
                rest.exchange("/api/v1/health", HttpMethod.OPTIONS, new HttpEntity<>(allowed), Void.class);
        assertThat(preflight.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preflight.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:8081");

        HttpHeaders edge = new HttpHeaders();
        edge.setOrigin("http://localhost:8088");
        edge.set("Access-Control-Request-Method", "POST");
        assertThat(rest.exchange("/api/v1/health", HttpMethod.OPTIONS, new HttpEntity<>(edge), Void.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        HttpHeaders other = new HttpHeaders();
        other.setOrigin("https://evil.example");
        other.set("Access-Control-Request-Method", "GET");
        ResponseEntity<Void> rejected =
                rest.exchange("/api/v1/health", HttpMethod.OPTIONS, new HttpEntity<>(other), Void.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void protectedPathsNeedAToken() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/does-not-exist", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        assertThat(response.getBody())
                .contains("\"code\":\"UNAUTHENTICATED\"")
                .contains("\"type\":\"https://soundcheck.app/problems/unauthenticated\"");
    }
}
