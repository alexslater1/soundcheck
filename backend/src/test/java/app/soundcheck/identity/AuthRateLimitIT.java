package app.soundcheck.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.shared.web.RateLimitProperties;
import app.soundcheck.support.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Auth limits: per IP (filter) and per email (service) → 429 with Retry-After and RateLimit-*
 * headers. Uses the JDK HttpClient: Apache HttpClient 5 behind TestRestTemplate honours Retry-After and
 * silently retries 429s, which hid the limit at first.
 */
@TestPropertySource(
        properties = {
            "soundcheck.rate-limit.auth.per-ip-per-minute=6",
            "soundcheck.rate-limit.auth.per-email-per-minute=2"
        })
class AuthRateLimitIT extends IntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    TestRestTemplate rest;

    @Autowired
    RateLimitProperties limits;

    @Test
    void perEmailThenPerIpLimitsReturn429WithHeaders() throws Exception {
        assertThat(limits.auth().perEmailPerMinute()).isEqualTo(2);
        assertThat(limits.auth().perIpPerMinute()).isEqualTo(6);
        String email = "limited-" + UUID.randomUUID() + "@example.com";

        // Per email: 2 allowed (401 wrong password), third → 429 from the service.
        assertThat(login(email).statusCode()).isEqualTo(401);
        assertThat(login(email).statusCode()).isEqualTo(401);
        HttpResponse<String> perEmail = login(email);
        assertThat(perEmail.statusCode()).as(perEmail.body()).isEqualTo(429);
        assertThat(perEmail.body()).contains("\"code\":\"RATE_LIMITED\"");
        assertThat(perEmail.headers().firstValue("Retry-After")).isPresent();
        assertThat(perEmail.headers().firstValue("RateLimit-Limit")).contains("2");

        // Per IP: 6 per minute in total from this address (3 used above); other emails exhaust it.
        assertThat(login("a-" + email).statusCode()).isEqualTo(401);
        assertThat(login("b-" + email).statusCode()).isEqualTo(401);
        assertThat(login("c-" + email).statusCode()).isEqualTo(401);
        HttpResponse<String> perIp = login("d-" + email);
        assertThat(perIp.statusCode()).as(perIp.body()).isEqualTo(429);
        assertThat(perIp.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(perIp.headers().firstValue("RateLimit-Limit")).contains("6");
        assertThat(perIp.headers().firstValue("Retry-After").orElse("")).matches("\\d+");
    }

    private HttpResponse<String> login(String email) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"not the password\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(rest.getRootUri() + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-Client", "android")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
