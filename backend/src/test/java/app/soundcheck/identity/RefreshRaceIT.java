package app.soundcheck.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.Parallel;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Two refreshes fired at the same moment with the same token. Rotation is a conditional
 * update ({@code ... where revoked_at is null}), so exactly one request wins; the other is treated as
 * reuse, which revokes the whole family, including the token the winner was just given.
 */
class RefreshRaceIT extends IntegrationTest {

    @Autowired
    TestRestTemplate rest;

    /** Repeated because which request wins, and at which statement the loser notices, varies per run. */
    @RepeatedTest(5)
    void twoParallelRefreshesWithOneTokenGiveOneSessionAndRevokeTheFamily() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map<String, Object>> registered = post(
                "/api/v1/auth/register",
                Map.of(
                        "email",
                        "rr-" + suffix + "@example.com",
                        "password",
                        "correct horse battery",
                        "handle",
                        "rr_" + suffix,
                        "displayName",
                        "Race"));
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = (String) registered.getBody().get("refreshToken");

        List<ResponseEntity<Map<String, Object>>> responses =
                Parallel.run(2, i -> post("/api/v1/auth/refresh", Map.of("refreshToken", token)));

        assertThat(responses)
                .filteredOn(r -> r.getStatusCode() == HttpStatus.OK)
                .hasSize(1);
        assertThat(responses)
                .filteredOn(r -> r.getStatusCode() != HttpStatus.OK)
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(r.getBody()).containsEntry("code", "REFRESH_REUSED");
                });

        // Family revoked: the token the winner received is already dead.
        String winnersToken = (String) responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.OK)
                .findFirst()
                .orElseThrow()
                .getBody()
                .get("refreshToken");
        ResponseEntity<Map<String, Object>> afterwards =
                post("/api/v1/auth/refresh", Map.of("refreshToken", winnersToken));
        assertThat(afterwards.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(afterwards.getBody()).containsEntry("code", "REFRESH_REUSED");
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
