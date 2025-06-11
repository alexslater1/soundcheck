package app.soundcheck.shared.web;

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
import org.springframework.test.context.TestPropertySource;

/**
 * 300 requests a minute per user (here 3, in its own context). Per user: one account at its
 * limit does not slow another. The JDK client, because TestRestTemplate's Apache client quietly retries
 * 429s (see {@code AuthRateLimitIT}).
 */
@TestPropertySource(properties = "soundcheck.rate-limit.per-user-per-minute=3")
class UserRateLimitIT extends IntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    TestRestTemplate rest;

    @Test
    void eachUserGetsTheirOwnBudgetOfRequestsAMinute() throws Exception {
        TestApi api = new TestApi(rest);
        TestApi.User busy = api.register("busy"); // /auth/** is not counted here
        TestApi.User other = api.register("other");

        for (int i = 0; i < 3; i++) {
            assertThat(get("/api/v1/me", busy).statusCode()).isEqualTo(200);
        }
        HttpResponse<String> limited = get("/api/v1/genres", busy);
        assertThat(limited.statusCode()).as(limited.body()).isEqualTo(429);
        assertThat(limited.body()).contains("\"code\":\"RATE_LIMITED\"");
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        assertThat(limited.headers().firstValue("RateLimit-Limit")).contains("3");

        assertThat(get("/api/v1/me", other).statusCode()).isEqualTo(200);
    }

    @Test
    void requestsWithoutATokenAreNotCountedAgainstAnyone() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(get("/api/v1/health", null).statusCode()).isEqualTo(200);
        }
    }

    private HttpResponse<String> get(String path, TestApi.User user) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(rest.getRootUri() + path))
                .header("X-Client", "android")
                .GET();
        if (user != null) {
            request.header("Authorization", "Bearer " + user.accessToken());
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
