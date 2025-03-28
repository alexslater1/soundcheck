package app.soundcheck.media;

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
 * Uploads are limited per user per hour (30 in production; 2 here so the limit can be reached).
 * Uses the JDK HttpClient for the same reason as {@code AuthRateLimitIT}: the Apache client behind
 * TestRestTemplate retries 429s by itself.
 */
@TestPropertySource(properties = "soundcheck.media.uploads-per-hour=2")
class UploadRateLimitIT extends IntegrationTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    TestRestTemplate rest;

    @Test
    void theThirdUploadWithinTheHourIsRefusedForThatUserOnly() throws Exception {
        TestApi api = new TestApi(rest);
        TestApi.User busy = api.register("busy");
        TestApi.User other = api.register("other");

        assertThat(open(busy).statusCode()).isEqualTo(201);
        assertThat(open(busy).statusCode()).isEqualTo(201);
        HttpResponse<String> third = open(busy);

        assertThat(third.statusCode()).isEqualTo(429);
        assertThat(third.body()).contains("RATE_LIMITED");
        assertThat(third.headers().firstValue("Retry-After")).isPresent();
        // The limit is per user.
        assertThat(open(other).statusCode()).isEqualTo(201);
    }

    private HttpResponse<String> open(TestApi.User user) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create(rest.getRootUri() + "/api/v1/media/uploads"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .header("X-Client", "android")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"kind\":\"VIDEO\",\"contentType\":\"video/mp4\",\"bytes\":1000}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
