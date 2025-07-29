package app.soundcheck.content;

import static app.soundcheck.content.PostClipsIT.clip;
import static app.soundcheck.content.PostClipsIT.ids;
import static app.soundcheck.content.PostClipsIT.ok;
import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two gig lists of the Create tab over HTTP: the suggestions (gigs the fan marked Going or Went and
 * gigs of acts they follow near home, from the last seven days up to today, newest first) and the
 * Pending list (gigs they went to and have not posted from, with no time limit).
 */
class CreateGigListsIT extends IntegrationTest {

    /** About a kilometre of latitude. */
    static final double KM_LAT = 1 / 111.0;

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

    TestApi api;
    TestData data;
    TestApi.User fan;
    String tag;
    Instant now;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        fan = api.register("creator");
        tag = UUID.randomUUID().toString().substring(0, 8);
        now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
    }

    @Test
    void suggestionsAreMyMarkedGigsAndMyActsGigsNearHomeFromTheLastWeekNewestFirst() {
        JsonNode cities = ok(api.get("/api/v1/cities?q=manchester", null));
        double lat = cities.path(0).path("lat").asDouble();
        double lng = cities.path(0).path("lng").asDouble();
        assertThat(api.patch(
                                "/api/v1/me",
                                Map.of("homeCityId", cities.path(0).path("id").asInt()),
                                fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        UUID near = data.venue("Near " + tag, lat + 2 * KM_LAT, lng);
        UUID far = data.venue("Far " + tag, lat + 200 * KM_LAT, lng);
        UUID followed = data.artist("Followed " + tag);
        UUID unknown = data.artist("Unknown " + tag);
        api.put("/api/v1/artists/" + followed + "/follow", null, fan);

        UUID followedNear = data.gig("A " + tag, near, now.minus(Duration.ofDays(2)), followed);
        UUID followedFar = data.gig("B " + tag, far, now.minus(Duration.ofDays(1)), followed);
        UUID notFollowed = data.gig("C " + tag, near, now.minus(Duration.ofDays(1)), unknown);
        UUID followedOld = data.gig("D " + tag, near, now.minus(Duration.ofDays(9)), followed);
        UUID followedLater = data.gig("E " + tag, near, now.plus(Duration.ofDays(2)), followed);
        UUID wentFar = data.gig("F " + tag, far, now.minus(Duration.ofDays(4)), unknown);
        UUID interestedNear = data.gig("G " + tag, far, now.minus(Duration.ofDays(3)), unknown);
        UUID goingFar = data.gig("H " + tag, far, now.minus(Duration.ofHours(5)), unknown);
        UUID cancelledNear = data.gig("I " + tag, near, now.minus(Duration.ofDays(1)), followed);
        dsl.execute("update catalog.gig set status = 'CANCELLED' where id = ?", cancelledNear);
        mark(wentFar, "WENT");
        // Marked before those gigs (the API refuses Going and Interested once a gig is over).
        markedBefore(interestedNear, "INTERESTED");
        markedBefore(goingFar, "GOING");

        List<String> suggested = ids(ok(api.get("/api/v1/me/gigs/to-post", fan)));
        assertThat(suggested).containsExactly(goingFar.toString(), followedNear.toString(), wentFar.toString());
        assertThat(suggested)
                .doesNotContain(
                        followedFar.toString(),
                        notFollowed.toString(),
                        followedOld.toString(),
                        followedLater.toString(),
                        interestedNear.toString(),
                        cancelledNear.toString());
    }

    @Test
    void pendingIsEveryGigIWentToAndHaveNotPostedFromNewestFirst() {
        UUID venue = data.venue("Venue " + tag);
        UUID act = data.artist("Act " + tag);
        UUID lastYear = data.gig("Old " + tag, venue, now.minus(Duration.ofDays(400)), act);
        UUID lastWeek = data.gig("Week " + tag, venue, now.minus(Duration.ofDays(6)), act);
        UUID posted = data.gig("Posted " + tag, venue, now.minus(Duration.ofDays(3)), act);
        UUID going = data.gig("Going " + tag, venue, now.minus(Duration.ofDays(2)), act);
        mark(lastYear, "WENT");
        mark(lastWeek, "WENT");
        mark(posted, "WENT");
        markedBefore(going, "GOING");
        // A Went from before the rules on a gig that was then cancelled: no clips to ask for.
        UUID cancelled = data.gig("Cancelled " + tag, venue, now.minus(Duration.ofDays(1)), act);
        mark(cancelled, "WENT");
        dsl.execute("update catalog.gig set status = 'CANCELLED' where id = ?", cancelled);
        assertThat(api.post(
                                "/api/v1/posts",
                                Map.of(
                                        "clips",
                                        List.of(clip(data.video(fan.id(), "READY"), null, null)),
                                        "gigId",
                                        posted),
                                fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // A Going whose gig is over is listed too, to ask "Did you go?".
        JsonNode pending = ok(api.get("/api/v1/me/gigs/unposted", fan));
        assertThat(ids(pending)).containsExactly(going.toString(), lastWeek.toString(), lastYear.toString());
        assertThat(pending.path(0).path("wentUnconfirmed").asBoolean()).isTrue();
        assertThat(pending.path(1).path("wentUnconfirmed").asBoolean()).isFalse();
    }

    /** A mark the fan made before the gig, stored as it was then. */
    private void markedBefore(UUID gig, String status) {
        dsl.execute(
                "insert into catalog.gig_attendance (user_id, gig_id, status, source, created_at, updated_at)"
                        + " values (?, ?, ?, 'USER', now(), now())",
                fan.id(),
                gig,
                status);
    }

    private void mark(UUID gig, String status) {
        assertThat(api.put("/api/v1/gigs/" + gig + "/attendance", Map.of("status", status), fan)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
    }
}
