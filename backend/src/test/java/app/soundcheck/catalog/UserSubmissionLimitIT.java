package app.soundcheck.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.shared.config.JsonMappers;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * The daily limits on what one fan may add to the catalog: 20 new gigs and 60 new
 * acts a day. Only additions count: finding a gig or an act that already exists is free, also after the
 * limit is reached. Each test uses a fresh account, so the in-memory buckets of other tests do not
 * matter. Uses the JDK HttpClient: the Apache client behind TestRestTemplate honours Retry-After and
 * would wait out a refusal (a day, here) before trying again.
 */
class UserSubmissionLimitIT extends IntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMappers.builder().build();

    @Autowired
    TestRestTemplate rest;

    TestApi api;
    TestApi.User fan;
    String tag;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        fan = api.register("adder");
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void aFanAddsAtMostTwentyNewGigsADayAndFindingAKnownOneIsFree() throws Exception {
        LocalDate day = LocalDate.now().minusDays(40);
        for (int i = 0; i < 20; i++) {
            assertThat(addGig(fan, "Act " + i + " " + tag, "Room " + i + " " + tag, day.minusDays(i))
                            .statusCode())
                    .as("gig %d", i)
                    .isEqualTo(201);
        }

        HttpResponse<String> refused = addGig(fan, "Act 20 " + tag, "Room 20 " + tag, day.minusDays(20));
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(429);
        assertThat(refused.headers().firstValue("Retry-After")).isPresent();
        assertThat(refused.headers().firstValue("RateLimit-Limit")).contains("20");

        // The same show as the first one (same venue, day and act): found, not added, so still allowed.
        HttpResponse<String> known = addGig(fan, "Act 0 " + tag, "Room 0 " + tag, day);
        assertThat(known.statusCode()).as(known.body()).isEqualTo(200);
        assertThat(JSON.readTree(known.body()).path("created").asBoolean()).isFalse();
    }

    @Test
    void aFanAddsAtMostSixtyNewActsADayAndAKnownNameIsStillFound() throws Exception {
        for (int i = 0; i < 60; i++) {
            assertThat(addArtist(fan, "Band " + i + " " + tag).statusCode())
                    .as("act %d", i)
                    .isEqualTo(201);
        }

        HttpResponse<String> refused = addArtist(fan, "Band 60 " + tag);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(429);
        assertThat(refused.headers().firstValue("RateLimit-Limit")).contains("60");

        assertThat(addArtist(fan, "band 7 " + tag).statusCode()).isEqualTo(200);
        // Another fan has their own allowance.
        assertThat(addArtist(api.register("other"), "Band 61 " + tag).statusCode())
                .isEqualTo(201);
    }

    private HttpResponse<String> addGig(TestApi.User user, String act, String venue, LocalDate date) throws Exception {
        return post(
                "/api/v1/gigs",
                Map.of(
                        "artistNames",
                        List.of(act),
                        "newVenue",
                        Map.of("name", venue, "cityName", "Manchester", "lat", 53.4808, "lng", -2.2426),
                        "date",
                        date.toString()),
                user);
    }

    private HttpResponse<String> addArtist(TestApi.User user, String name) throws Exception {
        return post("/api/v1/artists", Map.of("name", name), user);
    }

    private HttpResponse<String> post(String path, Object body, TestApi.User user) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(rest.getRootUri() + path))
                .header("Content-Type", "application/json")
                .header("X-Client", "android")
                .header("Authorization", "Bearer " + user.accessToken())
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
