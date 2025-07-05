package app.soundcheck.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.Parallel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
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
 * update ({@code ... where revoked_at is null}), so exactly one request wins the rotation. Before the grace period,
 * the other was treated as reuse, which revoked the whole family; now it finds a token rotated a moment
 * ago whose successor nobody has presented, which is how a lost answer looks, so it is answered too and
 * the winner's (unused) token is replaced. Exactly one of the two tokens stays live, and presenting the
 * other one is reuse.
 */
class RefreshRaceIT extends IntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DSLContext dsl;

    /** Repeated because which request wins, and at which statement the other notices, varies per run. */
    @RepeatedTest(5)
    void twoParallelRefreshesWithOneTokenBothAnswerAndLeaveExactlyOneLiveToken() throws Exception {
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

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK));
        List<String> given = responses.stream()
                .map(r -> (String) r.getBody().get("refreshToken"))
                .toList();
        List<String> live = given.stream().filter(this::live).toList();
        assertThat(live).hasSize(1);
        String dead = given.stream().filter(t -> !live.contains(t)).findFirst().orElseThrow();

        // The live one carries the session on; the replaced one was never meant to be used again.
        ResponseEntity<Map<String, Object>> next = post("/api/v1/auth/refresh", Map.of("refreshToken", live.get(0)));
        assertThat(next.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map<String, Object>> reuse = post("/api/v1/auth/refresh", Map.of("refreshToken", dead));
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reuse.getBody()).containsEntry("code", "REFRESH_REUSED");

        // Reuse revoked the family: the token just given out is dead too.
        String latest = (String) next.getBody().get("refreshToken");
        ResponseEntity<Map<String, Object>> afterwards = post("/api/v1/auth/refresh", Map.of("refreshToken", latest));
        assertThat(afterwards.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(afterwards.getBody()).containsEntry("code", "REFRESH_REUSED");
    }

    private boolean live(String raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return Boolean.TRUE.equals(
                    dsl.fetchValue("select revoked_at is null from identity.refresh_token where token_hash = ?", hash));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
