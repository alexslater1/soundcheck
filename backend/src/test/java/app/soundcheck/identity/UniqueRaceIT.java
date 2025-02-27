package app.soundcheck.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.Parallel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Two requests can both pass the "is this email/handle free?" check and then race on
 * the insert/update. The database's unique constraint decides the winner; the loser must get the same
 * {@code 409 EMAIL_TAKEN} / {@code HANDLE_TAKEN} as the sequential case, never a 500.
 */
class UniqueRaceIT extends IntegrationTest {

    private static final int PARALLEL = 6;

    @Autowired
    TestRestTemplate rest;

    @Test
    void concurrentRegistersWithOneEmailGiveOneAccountAndConflictsForTheRest() throws Exception {
        String email = "race-" + UUID.randomUUID() + "@example.com";
        List<ResponseEntity<Map<String, Object>>> responses =
                Parallel.run(PARALLEL, i -> register(email, "race_" + shortId() + i));

        assertThat(responses)
                .filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED)
                .hasSize(1);
        assertThat(responses)
                .filteredOn(r -> r.getStatusCode() != HttpStatus.CREATED)
                .hasSize(PARALLEL - 1)
                .allSatisfy(r -> {
                    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(r.getBody()).containsEntry("code", "EMAIL_TAKEN");
                });
    }

    @Test
    void concurrentRegistersWithOneHandleGiveOneAccountAndConflictsForTheRest() throws Exception {
        String handle = "race_" + shortId();
        List<ResponseEntity<Map<String, Object>>> responses =
                Parallel.run(PARALLEL, i -> register("race-" + UUID.randomUUID() + "@example.com", handle));

        assertThat(responses)
                .filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED)
                .hasSize(1);
        assertThat(responses)
                .filteredOn(r -> r.getStatusCode() != HttpStatus.CREATED)
                .hasSize(PARALLEL - 1)
                .allSatisfy(r -> {
                    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(r.getBody()).containsEntry("code", "HANDLE_TAKEN");
                });
    }

    @Test
    void concurrentRenamesToOneHandleGiveOneWinnerAndConflictsForTheRest() throws Exception {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < PARALLEL; i++) {
            ResponseEntity<Map<String, Object>> registered =
                    register("rename-" + UUID.randomUUID() + "@example.com", "rename_" + shortId() + i);
            assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            tokens.add((String) registered.getBody().get("accessToken"));
        }
        String wanted = "wanted_" + shortId();

        List<ResponseEntity<Map<String, Object>>> responses = Parallel.run(
                PARALLEL, i -> call(HttpMethod.PATCH, "/api/v1/me", Map.of("handle", wanted), tokens.get(i)));

        assertThat(responses)
                .filteredOn(r -> r.getStatusCode() == HttpStatus.OK)
                .hasSize(1);
        assertThat(responses)
                .filteredOn(r -> r.getStatusCode() != HttpStatus.OK)
                .hasSize(PARALLEL - 1)
                .allSatisfy(r -> {
                    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(r.getBody()).containsEntry("code", "HANDLE_TAKEN");
                });
    }

    private ResponseEntity<Map<String, Object>> register(String email, String handle) {
        return call(
                HttpMethod.POST,
                "/api/v1/auth/register",
                Map.of("email", email, "password", "correct horse battery", "handle", handle, "displayName", "Race"),
                null);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, Object body, String access) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Client", "android");
        if (access != null) {
            headers.setBearerAuth(access);
        }
        ResponseEntity<Map> response = rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
        return (ResponseEntity) response;
    }
}
