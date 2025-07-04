package app.soundcheck.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.catalog.VenueDraft;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * Gig stats over HTTP against real PostGIS: a fan's stats counted from the gigs they marked WENT, the
 * gigs behind each line, and who may see them. The fan's history is built by hand, so every number is
 * known: Cardinals play two bills on one night (two times seen), Keo is seen once as support, two venue
 * spellings of one city count as one city, and GOING / INTERESTED rows, other fans' rows and Went shows
 * that did not happen (still to come, cancelled, postponed) or have no bill count for nothing.
 */
class GigStatsIT extends IntegrationTest {

    static final ZoneId UK = ZoneId.of("Europe/London");

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
    TestApi.User other;
    String tag;
    int year;

    UUID keo;
    UUID cardinals;
    UUID westside;
    UUID scala;
    UUID chalk;
    UUID bandOnTheWall;
    UUID g1;
    UUID g2;
    UUID g3;
    UUID g4;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        fan = api.register("fan");
        other = api.register("other");
        tag = UUID.randomUUID().toString().substring(0, 8);
        year = LocalDate.now(UK).getYear();

        keo = data.artist("Keo " + tag);
        cardinals = data.artist("Cardinals " + tag);
        westside = data.artist("Westside Cowboy " + tag);
        UUID stranger = data.artist("Stranger " + tag);
        scala = data.venue("Scala " + tag); // city "Manchester"
        chalk = data.venue("CHALK " + tag, 50.8225, -0.1372); // city "Testville"
        // Another source's spelling of the same city: lower case, a stray space.
        bandOnTheWall = tx.execute(status -> catalogIngest.createVenue(new VenueDraft(
                "Band on the Wall " + tag, null, " manchester", null, 53.4855, -2.2369, "Europe/London")));

        // Fixed past years, so the year lines hold whatever the date: two years ago and last year.
        // Two years ago: Cardinals support Keo at Scala, then headline CHALK later the same night.
        g1 = data.gig("Keo " + tag, scala, at(year - 2, 3, 1, 19), keo, cardinals);
        g2 = data.gig("Cardinals " + tag, chalk, at(year - 2, 3, 1, 22), cardinals);
        // Last year: Keo headlines Scala with Westside Cowboy, then supports Westside Cowboy.
        g3 = data.gig("Keo " + tag, scala, at(year - 1, 1, 10, 20), keo, westside);
        g4 = data.gig("Westside Cowboy " + tag, bandOnTheWall, at(year - 1, 2, 10, 20), westside, keo);
        UUID going = data.gig("Keo " + tag, scala, at(year - 1, 2, 20, 20), keo);
        UUID interested = data.gig("Stranger " + tag, chalk, at(year - 1, 2, 21, 20), stranger);
        UUID othersOnly = data.gig("Stranger " + tag, scala, at(year - 1, 2, 22, 20), stranger);
        // Marked Went but never seen: a show still to come, and one that was cancelled.
        UUID notYet = data.gig("Keo " + tag, chalk, Instant.now().plus(java.time.Duration.ofDays(30)), keo);
        UUID cancelled = data.gig("Keo " + tag, chalk, at(year - 1, 3, 5, 20), keo, stranger);
        dsl.execute("update catalog.gig set status = 'CANCELLED' where id = ?", cancelled);
        // Postponed (Ticketmaster keeps the old date until a new one is set), and a gig with no bill.
        UUID postponed = data.gig("Keo " + tag, scala, at(year - 1, 4, 1, 20), keo);
        dsl.execute("update catalog.gig set status = 'POSTPONED' where id = ?", postponed);
        UUID noBill = data.gig("Mystery night " + tag, bandOnTheWall, at(year - 1, 4, 2, 20));

        for (UUID gig : List.of(g1, g2, g3, g4, notYet, cancelled, postponed, noBill)) {
            mark(fan, gig, "WENT");
        }
        mark(fan, going, "GOING");
        mark(fan, interested, "INTERESTED");
        mark(other, g2, "WENT");
        mark(other, othersOnly, "WENT");
    }

    @Test
    void theOwnerSeesTotalsYearsAndTheMostSeenActsAndVenues() {
        JsonNode stats = ok(api.get("/api/v1/users/" + fan.id() + "/stats", fan));

        JsonNode totals = stats.path("totals");
        assertThat(totals.path("gigs").asInt()).isEqualTo(4);
        assertThat(totals.path("acts").asInt()).isEqualTo(3);
        assertThat(totals.path("venues").asInt()).isEqualTo(3);
        assertThat(totals.path("cities").asInt()).isEqualTo(2);
        assertThat(stats.path("currentYear").asInt()).isEqualTo(year);

        // Newest year first; distinct counts within each year; nothing for the gig still to come.
        assertThat(lines(stats.path("years"), "year", "gigs", "acts", "venues"))
                .containsExactly((year - 1) + " 2 2 2", (year - 2) + " 2 2 2");

        // Keo three times (twice headlining, once supporting); Cardinals twice on one night; ties go to
        // the act seen most recently.
        assertThat(lines(stats.path("acts"), "name", "times"))
                .containsExactly("Keo " + tag + " 3", "Westside Cowboy " + tag + " 2", "Cardinals " + tag + " 2");
        JsonNode keoLine = stats.path("acts").path(0);
        assertThat(keoLine.path("id").asText("")).isEqualTo(keo.toString());
        // The cancelled show (5 March) and the one still to come are not Keo's "last".
        assertThat(keoLine.path("first").asText(""))
                .isEqualTo(at(year - 2, 3, 1, 19).toString());
        assertThat(keoLine.path("last").asText(""))
                .isEqualTo(at(year - 1, 2, 10, 20).toString());
        JsonNode cardinalsLine = stats.path("acts").path(2);
        assertThat(cardinalsLine.path("first").asText(""))
                .isEqualTo(at(year - 2, 3, 1, 19).toString());
        assertThat(cardinalsLine.path("last").asText(""))
                .isEqualTo(at(year - 2, 3, 1, 22).toString());

        assertThat(lines(stats.path("venues"), "name", "city", "times"))
                .containsExactly(
                        "Scala " + tag + " Manchester 2",
                        "Band on the Wall " + tag + "  manchester 1",
                        "CHALK " + tag + " Testville 1");

        // ?top= cuts both lists.
        JsonNode top2 = ok(api.get("/api/v1/users/" + fan.id() + "/stats?top=2", fan));
        assertThat(top2.path("acts")).hasSize(2);
        assertThat(top2.path("venues")).hasSize(2);
        assertThat(top2.path("totals").path("acts").asInt()).isEqualTo(3);
    }

    @Test
    void eachLineOpensTheGigsBehindItNewestFirst() {
        String base = "/api/v1/users/" + fan.id() + "/stats/gigs";

        assertThat(ids(ok(api.get(base + "?artistId=" + cardinals, fan)))).containsExactly(g2, g1);
        assertThat(ids(ok(api.get(base + "?artistId=" + keo, fan)))).containsExactly(g4, g3, g1);
        assertThat(ids(ok(api.get(base + "?venueId=" + scala, fan)))).containsExactly(g3, g1);
        assertThat(ids(ok(api.get(base, fan)))).containsExactly(g4, g3, g2, g1);

        // Pages by cursor without repeats.
        JsonNode first = ok(api.get(base + "?limit=3", fan));
        assertThat(ids(first)).containsExactly(g4, g3, g2);
        JsonNode second =
                ok(api.get(base + "?limit=3&cursor=" + first.path("nextCursor").asText(""), fan));
        assertThat(ids(second)).containsExactly(g1);
        assertThat(second.path("nextCursor").isNull()).isTrue();

        assertThat(ok(api.get(base + "?artistId=" + keo, fan))
                        .path("items")
                        .path(0)
                        .path("myAttendance")
                        .asText(""))
                .isEqualTo("WENT");
    }

    @Test
    void eachLineCountsAllItsGigsNotOnePage() {
        String count = "/api/v1/users/" + fan.id() + "/stats/gigs/count";

        // The same gigs as the lists above (the ones that happened), whatever the page size.
        assertThat(ok(api.get(count + "?artistId=" + keo, fan)).path("gigs").asInt())
                .isEqualTo(3);
        assertThat(ok(api.get(count + "?artistId=" + cardinals, fan))
                        .path("gigs")
                        .asInt())
                .isEqualTo(2);
        assertThat(ok(api.get(count + "?venueId=" + scala, fan)).path("gigs").asInt())
                .isEqualTo(2);
        assertThat(ok(api.get(count, fan)).path("gigs").asInt()).isEqualTo(4);

        // More than a page (50) of Went gigs with one act: every one is counted.
        UUID busy = data.artist("Busy " + tag);
        for (int i = 0; i < 52; i++) {
            mark(fan, data.gig("Busy " + tag, scala, at(year - 1, 6, 1, 0).plusSeconds(3600L * i), busy), "WENT");
        }
        assertThat(ok(api.get(count + "?artistId=" + busy, fan)).path("gigs").asInt())
                .isEqualTo(52);
        assertThat(ok(api.get("/api/v1/users/" + fan.id() + "/stats/gigs?artistId=" + busy + "&limit=50", fan))
                        .path("nextCursor")
                        .isNull())
                .isFalse();

        // Hidden like the rest of the stats until shared.
        assertProblem(api.get(count, other), HttpStatus.FORBIDDEN, "STATS_HIDDEN");
    }

    @Test
    void statsAreHiddenFromOthersUntilTheOwnerSharesThemAndFromBlockedPeople() {
        String stats = "/api/v1/users/" + fan.id() + "/stats";

        assertThat(ok(api.get("/api/v1/me", fan)).path("showGigStats").asBoolean())
                .isFalse();
        assertThat(ok(api.get("/api/v1/users/" + fan.handle(), fan))
                        .path("statsVisible")
                        .asBoolean())
                .isTrue();
        assertThat(ok(api.get("/api/v1/users/" + fan.handle(), other))
                        .path("statsVisible")
                        .asBoolean())
                .isFalse();
        assertProblem(api.get(stats, other), HttpStatus.FORBIDDEN, "STATS_HIDDEN");
        assertProblem(api.get(stats + "/gigs", other), HttpStatus.FORBIDDEN, "STATS_HIDDEN");

        JsonNode me = ok(api.patch("/api/v1/me", Map.of("showGigStats", true), fan));
        assertThat(me.path("showGigStats").asBoolean()).isTrue();
        assertThat(ok(api.get("/api/v1/users/" + fan.handle(), other))
                        .path("statsVisible")
                        .asBoolean())
                .isTrue();
        assertThat(ok(api.get(stats, other)).path("totals").path("gigs").asInt())
                .isEqualTo(4);
        // The other fan sees their own attendance in the list, not the owner's.
        JsonNode cardinalsGigs = ok(api.get(stats + "/gigs?artistId=" + cardinals, other));
        assertThat(ids(cardinalsGigs)).containsExactly(g2, g1);
        assertThat(cardinalsGigs.path("items").path(0).path("myAttendance").asText(""))
                .isEqualTo("WENT");
        assertThat(cardinalsGigs.path("items").path(1).path("myAttendance").isNull())
                .isTrue();

        // Another edit of the profile leaves the choice alone.
        assertThat(ok(api.patch("/api/v1/me", Map.of("bio", "gig diary"), fan))
                        .path("showGigStats")
                        .asBoolean())
                .isTrue();

        // The viewer blocked the owner: the profile is there, its stats are not.
        assertThat(api.put("/api/v1/users/" + fan.id() + "/block", null, other).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(ok(api.get("/api/v1/users/" + fan.handle(), other))
                        .path("statsVisible")
                        .asBoolean())
                .isFalse();
        assertProblem(api.get(stats, other), HttpStatus.FORBIDDEN, "STATS_HIDDEN");
        api.delete("/api/v1/users/" + fan.id() + "/block", other);

        // The owner blocked the viewer: no profile, so no stats either.
        api.put("/api/v1/users/" + other.id() + "/block", null, fan);
        assertProblem(api.get(stats, other), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        api.delete("/api/v1/users/" + other.id() + "/block", fan);
        assertThat(ok(api.get(stats, other)).path("totals").path("gigs").asInt())
                .isEqualTo(4);

        // Sharing can be turned off again.
        api.patch("/api/v1/me", Map.of("showGigStats", false), fan);
        assertProblem(api.get(stats, other), HttpStatus.FORBIDDEN, "STATS_HIDDEN");
    }

    @Test
    void aFanWithNoGigsHasEmptyStatsAndBadRequestsAreRefused() {
        TestApi.User newcomer = api.register("newcomer");
        JsonNode stats = ok(api.get("/api/v1/users/" + newcomer.id() + "/stats", newcomer));
        assertThat(stats.path("totals").path("gigs").asInt()).isZero();
        assertThat(stats.path("totals").path("cities").asInt()).isZero();
        assertThat(stats.path("years")).isEmpty();
        assertThat(stats.path("acts")).isEmpty();
        assertThat(stats.path("venues")).isEmpty();

        assertProblem(
                api.get("/api/v1/users/" + UUID.randomUUID() + "/stats", fan), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        assertThat(api.get("/api/v1/users/" + fan.id() + "/stats?top=0", fan).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.get("/api/v1/users/" + fan.id() + "/stats?top=101", fan).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.get("/api/v1/users/" + fan.id() + "/stats?top=100", fan).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private void mark(TestApi.User user, UUID gig, String status) {
        assertThat(api.put("/api/v1/gigs/" + gig + "/attendance", Map.of("status", status), user)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private static Instant at(int year, int month, int day, int hourUtc) {
        return LocalDate.of(year, month, day).atTime(hourUtc, 0).toInstant(ZoneOffset.UTC);
    }

    private static List<String> lines(JsonNode array, String... fields) {
        List<String> lines = new ArrayList<>();
        for (JsonNode node : array) {
            List<String> parts = new ArrayList<>();
            for (String field : fields) {
                parts.add(node.path(field).asText(""));
            }
            lines.add(String.join(" ", parts));
        }
        return lines;
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.path("items")
                .forEach(item -> ids.add(UUID.fromString(item.path("id").asText(""))));
        return ids;
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(status);
        assertThat(response.getBody().path("code").asText("")).isEqualTo(code);
    }
}
