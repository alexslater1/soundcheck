package app.soundcheck.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

/**
 * Deleting the account asks for the password, so it must not be a way around login's limit.
 * With a stolen access token, {@code DELETE /me} could otherwise test passwords as fast as Argon2
 * allows. It shares the per-email budget with login and register (here 3 a minute; this class has
 * its own limits, so no other test's requests count). The JDK client, because TestRestTemplate's
 * Apache client quietly retries 429s (see {@code AuthRateLimitIT}).
 */
@TestPropertySource(
        properties = {
            "soundcheck.rate-limit.auth.per-ip-per-minute=100",
            "soundcheck.rate-limit.auth.per-email-per-minute=3"
        })
class AccountDeletionLimitIT extends IntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    TestRestTemplate rest;

    @Test
    void guessingThePasswordToDeleteTheAccountIsLimitedLikeLogin() throws Exception {
        TestApi.User user = new TestApi(rest).register("deleter"); // 1 of 3 (register counts)

        assertThat(deleteMe(user, "wrong password 1").statusCode()).isEqualTo(401); // 2 of 3
        assertThat(deleteMe(user, "wrong password 2").statusCode()).isEqualTo(401); // 3 of 3
        HttpResponse<String> limited = deleteMe(user, "correct horse battery");
        assertThat(limited.statusCode()).as(limited.body()).isEqualTo(429);
        assertThat(limited.body()).contains("\"code\":\"RATE_LIMITED\"");
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();

        // even the right password waited: the account is still there
        assertThat(new TestApi(rest).get("/api/v1/me", user).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anOverlongPasswordIsRefusedWithoutBeingHashed() throws Exception {
        TestApi.User user = new TestApi(rest).register("longpw");
        HttpResponse<String> response = deleteMe(user, "x".repeat(10_000));
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"INVALID_CREDENTIALS\"");
    }

    private HttpResponse<String> deleteMe(TestApi.User user, String password) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(rest.getRootUri() + "/api/v1/me"))
                .header("Content-Type", "application/json")
                .header("X-Client", "android")
                .header("Authorization", "Bearer " + user.accessToken())
                .method("DELETE", HttpRequest.BodyPublishers.ofString("{\"password\":\"" + password + "\"}"))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
