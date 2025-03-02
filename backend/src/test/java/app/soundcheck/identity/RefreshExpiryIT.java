package app.soundcheck.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.MutableClock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * Refresh tokens live 30 days. The application reads time from an injected
 * {@code Clock}; the tests move it instead of waiting.
 */
class RefreshExpiryIT extends IntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    MutableClock clock;

    @AfterEach
    void backToRealTime() {
        clock.reset();
    }

    @Test
    void aRefreshTokenWorksOnDay29AndIsExpiredAfterDay30() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map<String, Object>> registered = post(
                "/api/v1/auth/register",
                Map.of(
                        "email",
                        "exp-" + suffix + "@example.com",
                        "password",
                        "correct horse battery",
                        "handle",
                        "exp_" + suffix,
                        "displayName",
                        "Expiry"));
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String first = (String) registered.getBody().get("refreshToken");

        clock.advance(Duration.ofDays(29));
        ResponseEntity<Map<String, Object>> day29 = post("/api/v1/auth/refresh", Map.of("refreshToken", first));
        assertThat(day29.getStatusCode()).isEqualTo(HttpStatus.OK);
        String second = (String) day29.getBody().get("refreshToken");

        // The rotated token got a fresh 30 days from day 29; one minute past that it is expired.
        clock.advance(Duration.ofDays(30).plusMinutes(1));
        ResponseEntity<Map<String, Object>> expired = post("/api/v1/auth/refresh", Map.of("refreshToken", second));
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(expired.getBody()).containsEntry("code", "REFRESH_EXPIRED");

        // Expiry is not theft: asking again gives the same answer (the family was not revoked as "reused").
        ResponseEntity<Map<String, Object>> again = post("/api/v1/auth/refresh", Map.of("refreshToken", second));
        assertThat(again.getBody()).containsEntry("code", "REFRESH_EXPIRED");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ResponseEntity<Map<String, Object>> post(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Client", "android");
        ResponseEntity<Map> response = rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        return (ResponseEntity) response;
    }
}
