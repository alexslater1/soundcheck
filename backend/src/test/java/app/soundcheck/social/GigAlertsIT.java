package app.soundcheck.social;

import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.social.internal.GigAlertService;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A new gig alerts the fans who follow one of its acts or its venue, or saved a search
 * it matches, once, in one inbox entry; nothing is sent when nothing is new, and past or cancelled gigs
 * never alert. The pass is {@link GigAlertService#run()} (its job is off in tests); each test runs it
 * once first, so gigs other tests created are already dealt with.
 */
class GigAlertsIT extends IntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    CatalogIngest catalogIngest;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    GigAlertService alerts;

    @Autowired
    Clock clock;

    TestApi api;
    TestData data;
    TestApi.User fan;
    TestApi.User bystander;
    String tag;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        fan = api.register("alertfan");
        bystander = api.register("bystander");
        tag = UUID.randomUUID().toString().substring(0, 8);
        alerts.run(); // starts alerts, or deals with what other tests created
    }

    @Test
    void aNewGigByAFollowedActAlertsItsFollowerOnceAndNobodyElse() {
        UUID keo = data.artist("Keo " + tag);
        UUID venue = data.venue("The Social " + tag);
        assertThat(api.put("/api/v1/artists/" + keo + "/follow", null, fan).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        UUID gig = data.gig("Keo " + tag + " live", venue, inDays(10), keo);

        alerts.run();

        JsonNode alert = awaitOneAlert(fan);
        assertThat(alert.path("gigCount").asInt()).isEqualTo(1);
        JsonNode item = alert.path("gigs").get(0);
        assertThat(item.path("gigId").asText("")).isEqualTo(gig.toString());
        assertThat(item.path("title").asText("")).isEqualTo("Keo " + tag + " live");
        assertThat(item.path("venueName").asText("")).isEqualTo("The Social " + tag);
        assertThat(item.path("reason").asText("")).isEqualTo("ARTIST");
        assertThat(item.path("because").asText("")).isEqualTo("Keo " + tag);

        // The alert's own page reads it back; someone else's notification is not found.
        JsonNode one = ok(api.get("/api/v1/notifications/" + alert.path("id").asText(""), fan));
        assertThat(one.path("gigs").get(0).path("gigId").asText("")).isEqualTo(gig.toString());
        assertThat(api.get("/api/v1/notifications/" + alert.path("id").asText(""), bystander)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Another pass finds nothing new for the fan: still one entry.
        alerts.run();
        stillAlerts(fan, 1);
        assertThat(newGigAlerts(bystander)).isEmpty();
    }

    @Test
    void aFollowedVenuesNewGigAlertsAndTheVenuePageShowsTheFollow() {
        UUID venue = data.venue("Moth " + tag);
        String path = "/api/v1/venues/" + venue;
        assertThat(api.put(path + "/follow", null, fan).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.put(path + "/follow", null, fan).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        JsonNode page = ok(api.get(path, fan));
        assertThat(page.path("following").asBoolean()).isTrue();
        assertThat(page.path("followerCount").asLong()).isEqualTo(1);
        assertThat(ok(api.get(path, bystander)).path("following").asBoolean()).isFalse();
        assertThat(api.put("/api/v1/venues/" + UUID.randomUUID() + "/follow", null, fan)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("VENUE_NOT_FOUND");

        UUID act = data.artist("Somebody " + tag);
        UUID gig = data.gig("Somebody " + tag, venue, inDays(4), act);
        alerts.run();

        JsonNode item = awaitOneAlert(fan).path("gigs").get(0);
        assertThat(item.path("gigId").asText("")).isEqualTo(gig.toString());
        assertThat(item.path("reason").asText("")).isEqualTo("VENUE");
        assertThat(item.path("because").asText("")).isEqualTo("Moth " + tag);

        // Unfollowing stops further alerts.
        assertThat(api.delete(path + "/follow", fan).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(ok(api.get(path, fan)).path("followerCount").asLong()).isZero();
        data.gig("Somebody else " + tag, venue, inDays(6), act);
        alerts.run();
        stillAlerts(fan, 1);
    }

    @Test
    void aSavedSearchAlertsUntilItsAlertsAreSwitchedOff() {
        UUID act = data.artist("Bleech " + tag);
        UUID venue = data.venue("Hope " + tag);
        Map<String, Object> search = new HashMap<>();
        search.put("name", "Bleech anywhere " + tag);
        search.put("artistIds", List.of(act.toString()));
        search.put("place", "UK");
        JsonNode saved = api.post("/api/v1/me/gig-filters", search, fan).getBody();
        assertThat(saved.path("alerts").asBoolean()).isTrue();

        UUID gig = data.gig("Bleech " + tag, venue, inDays(12), act);
        alerts.run();
        JsonNode item = awaitOneAlert(fan).path("gigs").get(0);
        assertThat(item.path("gigId").asText("")).isEqualTo(gig.toString());
        assertThat(item.path("reason").asText("")).isEqualTo("SEARCH");
        assertThat(item.path("because").asText("")).isEqualTo("Bleech anywhere " + tag);

        JsonNode off =
                ok(api.patch("/api/v1/me/gig-filters/" + saved.path("id").asText(""), Map.of("alerts", false), fan));
        assertThat(off.path("alerts").asBoolean()).isFalse();
        assertThat(api.patch("/api/v1/me/gig-filters/" + saved.path("id").asText(""), Map.of("alerts", true), bystander)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        data.gig("Bleech again " + tag, venue, inDays(20), act);
        alerts.run();
        stillAlerts(fan, 1);
    }

    @Test
    void pastAndCancelledGigsDoNotAlertAndNothingNewSendsNothing() {
        UUID act = data.artist("Past " + tag);
        UUID venue = data.venue("Old Hall " + tag);
        api.put("/api/v1/artists/" + act + "/follow", null, fan);

        alerts.run(); // nothing new yet
        data.gig("Yesterday " + tag, venue, inDays(-1), act);
        UUID cancelled = data.gig("Called off " + tag, venue, inDays(5), act);
        tx.executeWithoutResult(s -> dsl.update(GIG)
                .set(GIG.STATUS, "CANCELLED")
                .where(GIG.ID.eq(cancelled))
                .execute());
        alerts.run();

        stillAlerts(fan, 0);
    }

    @Test
    void aFanAddedGigAlertsFollowersButNotTheFanWhoAddedItWhateverTheirBlocks() {
        TestApi.User adder = api.register("adder");
        UUID act = data.artist("Westside " + tag);
        UUID venue = data.venue("Dome " + tag);
        api.put("/api/v1/artists/" + act + "/follow", null, fan);
        api.put("/api/v1/artists/" + act + "/follow", null, adder);
        // A block hides two people from each other; an alert names no one, so it still arrives.
        assertThat(api.put("/api/v1/users/" + adder.id() + "/block", null, fan).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, Object> body = new HashMap<>();
        body.put("artistIds", List.of(act.toString()));
        body.put("venueId", venue.toString());
        body.put(
                "date",
                LocalDate.ofInstant(inDays(15), ZoneId.of("Europe/London")).toString());
        ResponseEntity<JsonNode> added = api.post("/api/v1/gigs", body, adder);
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        alerts.run();
        JsonNode item = awaitOneAlert(fan).path("gigs").get(0);
        assertThat(item.path("gigId").asText(""))
                .isEqualTo(added.getBody().path("item").path("id").asText(""));
        stillAlerts(adder, 0);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Instant inDays(int days) {
        return Instant.now(clock).plus(Duration.ofDays(days));
    }

    private JsonNode awaitOneAlert(TestApi.User user) {
        await().atMost(Duration.ofSeconds(15)).until(() -> newGigAlerts(user).size() == 1);
        return newGigAlerts(user).getFirst();
    }

    /** The outbox delivers after the commit: give it time, then the count must still be what it was. */
    private void stillAlerts(TestApi.User user, int count) {
        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(6))
                .until(() -> newGigAlerts(user).size() == count);
    }

    private List<JsonNode> newGigAlerts(TestApi.User user) {
        List<JsonNode> out = new ArrayList<>();
        ok(api.get("/api/v1/notifications?limit=50", user)).path("items").forEach(n -> {
            if ("NEW_GIGS".equals(n.path("type").asText(""))) {
                out.add(n);
            }
        });
        return out;
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("%s", response.getBody())
                .isTrue();
        return response.getBody();
    }
}
