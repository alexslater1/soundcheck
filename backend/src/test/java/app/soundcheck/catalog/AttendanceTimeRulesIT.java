package app.soundcheck.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.ingestion.internal.RunStats;
import app.soundcheck.ingestion.internal.resolve.EntityResolver;
import app.soundcheck.ingestion.internal.source.SourceArtist;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceVenue;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Attendance follows the gig's UK day. Interested and Going until the day ends, Went from
 * the day on, nothing on a cancelled gig; a Going on a gig that is over reads as Went everywhere (the
 * gig's counts, the fan's lists, the stats) and Pending asks "Did you go?".
 */
class AttendanceTimeRulesIT extends IntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    EntityResolver resolver;

    @Autowired
    DSLContext dsl;

    TestApi api;
    TestApi.User fan;
    String tag;
    SourceVenue venue;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        fan = api.register("rules");
        tag = UUID.randomUUID().toString().substring(0, 8);
        venue = new SourceVenue(
                "v-rules-" + tag, "Venue rules " + tag, null, "Testville", null, 61.9, -1.4, "Europe/London");
    }

    @Test
    void eachStatusIsAllowedOnlyWhenTheGigsDayAllowsIt() {
        Instant now = Instant.now();
        UUID future = gig("future", now.plus(Duration.ofDays(3)));
        UUID today = gig("today", GigTime.startOfToday(now).plus(Duration.ofMinutes(1)));
        UUID past = gig("past", now.minus(Duration.ofDays(3)));
        UUID cancelled = gig("cancelled", now.plus(Duration.ofDays(4)));
        dsl.execute("update catalog.gig set status = 'CANCELLED' where id = ?", cancelled);

        assertThat(code(put(future, "WENT"))).isEqualTo("GIG_NOT_HAPPENED");
        assertThat(put(future, "WENT").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(put(future, "INTERESTED").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put(future, "GOING").getStatusCode()).isEqualTo(HttpStatus.OK);

        // On the day all three are allowed: a fan at the gig says Went, one on the way still says Going.
        for (String status : List.of("INTERESTED", "GOING", "WENT")) {
            assertThat(put(today, status).getStatusCode()).as(status).isEqualTo(HttpStatus.OK);
        }

        assertThat(code(put(past, "GOING"))).isEqualTo("GIG_IS_OVER");
        assertThat(code(put(past, "INTERESTED"))).isEqualTo("GIG_IS_OVER");
        assertThat(put(past, "WENT").getStatusCode()).isEqualTo(HttpStatus.OK);

        for (String status : List.of("INTERESTED", "GOING", "WENT")) {
            assertThat(code(put(cancelled, status))).as(status).isEqualTo("GIG_CANCELLED");
        }
        // Clearing is always allowed, even on a gig that is over.
        assertThat(api.delete("/api/v1/gigs/" + past + "/attendance", fan).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void aGoingOnAGigThatIsOverReadsAsWentUntilTheFanConfirmsOrRemovesIt() {
        Instant now = Instant.now();
        UUID past = gig("over", now.minus(Duration.ofDays(2)));
        UUID upcoming = gig("soon", now.plus(Duration.ofDays(2)));
        TestApi.User other = api.register("other");
        TestApi.User curious = api.register("curious");
        // Marks made before the gig, stored as they were then (the API now refuses Going on a past gig).
        mark(fan, past, "GOING");
        mark(other, past, "WENT");
        mark(curious, past, "INTERESTED");
        ok(put(upcoming, "GOING"));

        JsonNode detail = ok(api.get("/api/v1/gigs/" + past, fan));
        assertThat(detail.path("gig").path("myAttendance").asText("")).isEqualTo("WENT");
        assertThat(detail.path("gig").path("wentUnconfirmed").asBoolean()).isTrue();
        assertThat(detail.path("attendanceCounts").path("went").asInt()).isEqualTo(2);
        assertThat(detail.path("attendanceCounts").path("going").asInt()).isZero();
        assertThat(ok(api.get("/api/v1/gigs/" + past, other))
                        .path("gig")
                        .path("wentUnconfirmed")
                        .asBoolean())
                .isFalse();

        // The fan's lists: with the Went gigs, not the Going ones.
        assertThat(titles(ok(api.get("/api/v1/me/gigs?status=WENT&when=past", fan))))
                .containsExactly("over " + tag);
        assertThat(titles(ok(api.get("/api/v1/me/gigs?status=GOING", fan)))).containsExactly("soon " + tag);
        assertThat(titles(ok(api.get("/api/v1/users/" + fan.id() + "/gigs/going", fan))))
                .containsExactly("soon " + tag);
        // The stats count it.
        assertThat(ok(api.get("/api/v1/users/" + fan.id() + "/stats", fan))
                        .path("totals")
                        .path("gigs")
                        .asInt())
                .isEqualTo(1);

        // Pending asks "Did you go?"; confirming makes it a plain Went.
        JsonNode pending = ok(api.get("/api/v1/me/gigs/unposted", fan));
        assertThat(pending.size()).isEqualTo(1);
        assertThat(pending.path(0).path("id").asText("")).isEqualTo(past.toString());
        assertThat(pending.path(0).path("wentUnconfirmed").asBoolean()).isTrue();
        ok(put(past, "WENT"));
        assertThat(ok(api.get("/api/v1/me/gigs/unposted", fan))
                        .path(0)
                        .path("wentUnconfirmed")
                        .asBoolean())
                .isFalse();
        assertThat(dsl.fetchValue(
                        "select status from catalog.gig_attendance where user_id = ? and gig_id = ?", fan.id(), past))
                .isEqualTo("WENT");
    }

    @Test
    void aGigOnTodayCountsInTheStatsBeforeItsStartTime() {
        // One definition of "happened": the gig's day has come, whatever the hour.
        Instant now = Instant.now();
        Instant later = GigTime.startOfTomorrow(now).minus(Duration.ofMinutes(1));
        UUID tonight = gig("tonight", later);
        ok(put(tonight, "WENT"));
        assertThat(ok(api.get("/api/v1/users/" + fan.id() + "/stats", fan))
                        .path("totals")
                        .path("gigs")
                        .asInt())
                .isEqualTo(1);
    }

    private ResponseEntity<JsonNode> put(UUID gigId, String status) {
        return api.put("/api/v1/gigs/" + gigId + "/attendance", Map.of("status", status), fan);
    }

    private void mark(TestApi.User user, UUID gigId, String status) {
        dsl.execute(
                "insert into catalog.gig_attendance (user_id, gig_id, status, source, created_at, updated_at)"
                        + " values (?, ?, ?, 'USER', now(), now())",
                user.id(),
                gigId,
                status);
    }

    private UUID gig(String title, Instant startsAt) {
        String externalId = "g-" + title + "-" + tag;
        resolver.ingest(
                new SourceGig(
                        "TICKETMASTER",
                        externalId,
                        null,
                        title + " " + tag,
                        startsAt,
                        null,
                        "SCHEDULED",
                        null,
                        null,
                        null,
                        null,
                        null,
                        venue,
                        List.of(new SourceArtist(
                                "a-" + title + "-" + tag, "Act " + title + " " + tag, null, List.of(), null))),
                new RunStats(),
                Instant.now());
        return dsl.fetchOne(
                        "select gig_id from catalog.gig_source where source = 'TICKETMASTER' and external_id = ?",
                        externalId)
                .get(0, UUID.class);
    }

    private static String code(ResponseEntity<JsonNode> response) {
        return response.getBody().path("code").asText("");
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static List<String> titles(JsonNode page) {
        return page.path("items")
                .valueStream()
                .map(item -> item.path("title").asText(""))
                .toList();
    }
}
