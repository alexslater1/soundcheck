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
 * A refresh whose answer never reached the app (the connection dropped, the app was suspended
 * or killed before it saved the new token) leaves the app holding a token the server has already rotated.
 * Presenting it again within the grace period, while the token that replaced it has never been presented,
 * rotates again instead of ending the session. Outside those conditions it is still reuse.
 */
class RefreshGraceIT extends IntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    MutableClock clock;

    @AfterEach
    void backToRealTime() {
        clock.reset();
    }

    @Test
    void anAnswerLostOnTheWayCanBeAskedForAgainAndTheSessionGoesOn() {
        String first = register();
        String lost = refresh(first).refreshToken(); // the app never sees this one

        clock.advance(Duration.ofSeconds(30));
        Answer again = refresh(first);
        assertThat(again.status()).isEqualTo(HttpStatus.OK);
        Answer next = refresh(again.refreshToken());
        assertThat(next.status()).isEqualTo(HttpStatus.OK);

        // The answer that was lost can never be used: whoever presents it is not the app.
        assertThat(refresh(lost).code()).isEqualTo("REFRESH_REUSED");
        assertThat(refresh(next.refreshToken()).code()).isEqualTo("REFRESH_REUSED");
    }

    @Test
    void twoLostAnswersInARowStillLeaveTheSession() {
        String first = register();
        refresh(first);
        clock.advance(Duration.ofSeconds(10));
        refresh(first);
        clock.advance(Duration.ofSeconds(10));

        Answer third = refresh(first);
        assertThat(third.status()).isEqualTo(HttpStatus.OK);
        assertThat(refresh(third.refreshToken()).status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void afterTheGracePeriodItIsReuseAndTheFamilyIsRevoked() {
        String first = register();
        String second = refresh(first).refreshToken();

        clock.advance(Duration.ofSeconds(61));

        assertThat(refresh(first).code()).isEqualTo("REFRESH_REUSED");
        assertThat(refresh(second).code()).isEqualTo("REFRESH_REUSED");
    }

    @Test
    void theGracePeriodRunsFromTheFirstRotationNotTheLastRetry() {
        String first = register();
        refresh(first);
        clock.advance(Duration.ofSeconds(40));
        String retried = refresh(first).refreshToken();
        clock.advance(Duration.ofSeconds(30)); // 70 s after the first rotation

        assertThat(refresh(first).code()).isEqualTo("REFRESH_REUSED");
        assertThat(refresh(retried).code()).isEqualTo("REFRESH_REUSED");
    }

    @Test
    void onceTheReplacementHasBeenPresentedTheOldTokenIsTheftEvenWithinTheGracePeriod() {
        String first = register();
        String second = refresh(first).refreshToken();
        String third = refresh(second).refreshToken();

        assertThat(refresh(first).code()).isEqualTo("REFRESH_REUSED");
        assertThat(refresh(third).code()).isEqualTo("REFRESH_REUSED");
    }

    @Test
    void signingOutIsFinalEvenWithinTheGracePeriod() {
        String first = register();
        String second = refresh(first).refreshToken();
        assertThat(post("/api/v1/auth/logout", Map.of("refreshToken", second)).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(refresh(first).code()).isEqualTo("REFRESH_REUSED");
    }

    record Answer(HttpStatus status, String refreshToken, String code) {}

    private String register() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map<String, Object>> registered = post(
                "/api/v1/auth/register",
                Map.of(
                        "email",
                        "grace-" + suffix + "@example.com",
                        "password",
                        "correct horse battery",
                        "handle",
                        "grace_" + suffix,
                        "displayName",
                        "Grace"));
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) registered.getBody().get("refreshToken");
    }

    private Answer refresh(String token) {
        ResponseEntity<Map<String, Object>> response = post("/api/v1/auth/refresh", Map.of("refreshToken", token));
        Map<String, Object> body = response.getBody();
        return new Answer(
                HttpStatus.valueOf(response.getStatusCode().value()),
                body == null ? null : (String) body.get("refreshToken"),
                body == null ? null : (String) body.get("code"));
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
