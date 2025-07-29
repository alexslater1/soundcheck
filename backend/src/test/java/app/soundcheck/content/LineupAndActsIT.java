package app.soundcheck.content;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.catalog.GigDraft;
import app.soundcheck.catalog.GigTime;
import app.soundcheck.shared.Ids;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Over HTTP against real PostGIS: fans edit a gig's bill and start time,
 * say which acts they saw, and a clip's own act decides the artist pages it is on.
 */
class LineupAndActsIT extends IntegrationTest {

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
    UUID venue;
    UUID keo;
    UUID tooth;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        fan = api.register("acts");
        tag = UUID.randomUUID().toString().substring(0, 8);
        venue = data.venue("Brudenell " + tag);
        keo = data.artist("KEO " + tag);
        tooth = data.artist("Tooth " + tag);
    }

    // ── editing a gig ───────────────────────────────────────────────────────────────────────────

    @Test
    void someoneGoingAddsActsAndFixesTheStartTimeAndEveryEditIsLogged() {
        Instant at = LocalDate.now(GigTime.UK)
                .plusDays(5)
                .atTime(19, 0)
                .atZone(GigTime.UK)
                .toInstant();
        UUID gig = data.gig("KEO " + tag, venue, at, keo);
        TestApi.User stranger = api.register("stranger");
        Map<String, Object> addTooth = Map.of("addArtistIds", List.of(tooth));

        assertProblem(api.patch(path(gig), addTooth, stranger), HttpStatus.FORBIDDEN, "NOT_ATTENDING");

        ok(api.put(path(gig) + "/attendance", Map.of("status", "GOING"), fan));
        JsonNode edited = ok(api.patch(
                path(gig),
                Map.of(
                        "addArtistIds", List.of(tooth),
                        "addArtistNames", List.of("Brand New " + tag),
                        "startTime", "21:15"),
                fan));

        assertThat(names(edited.path("lineup"))).containsExactly("KEO " + tag, "Tooth " + tag, "Brand New " + tag);
        assertThat(Instant.parse(edited.path("gig").path("startsAt").asText("")))
                .isEqualTo(at.atZone(GigTime.UK).with(LocalTime.of(21, 15)).toInstant());
        assertThat(edited.path("addedByMe").asBoolean()).isFalse();
        assertThat(dsl.fetch(
                                "select field, user_id from catalog.gig_edit where gig_id = ? order by created_at, field",
                                gig)
                        .map(r -> r.get(0) + " " + r.get(1)))
                .containsExactlyInAnyOrder("ACT_ADDED " + fan.id(), "ACT_ADDED " + fan.id(), "STARTS_AT " + fan.id());

        // Adding an act already on the bill changes nothing and logs nothing.
        ok(api.patch(path(gig), addTooth, fan));
        assertThat(dsl.fetchCount(dsl.selectFrom("catalog.gig_edit").where("gig_id = ?", gig)))
                .isEqualTo(3);
        // Only the fan who added a gig changes its date or venue; this one came from a source.
        assertProblem(
                api.patch(
                        path(gig),
                        Map.of("date", LocalDate.now(GigTime.UK).plusDays(6).toString()),
                        fan),
                HttpStatus.FORBIDDEN,
                "NOT_YOUR_GIG");

        // The source's next listing rewrites its part of the bill; the fans' acts stay after it.
        tx.executeWithoutResult(status -> catalogIngest.updateGig(gig, draft(at, List.of(keo))));
        assertThat(lineup(gig)).containsExactly("KEO " + tag, "Tooth " + tag, "Brand New " + tag);
        tx.executeWithoutResult(status -> catalogIngest.updateGig(gig, draft(at, List.of(tooth, keo))));
        assertThat(lineup(gig)).containsExactly("Tooth " + tag, "KEO " + tag, "Brand New " + tag);
    }

    @Test
    void theFanWhoAddedAGigChangesItsDateAndVenue() {
        LocalDate day = LocalDate.now(GigTime.UK).plusDays(10);
        UUID other = data.venue("Hope and Anchor " + tag);
        JsonNode added = api.post(
                        "/api/v1/gigs",
                        Map.of("artistNames", List.of("KEO " + tag), "venueId", venue, "date", day.toString()),
                        fan)
                .getBody();
        UUID gig = UUID.fromString(added.path("item").path("id").asText(""));
        assertThat(ok(api.get(path(gig), fan)).path("addedByMe").asBoolean()).isTrue();

        JsonNode moved = ok(api.patch(path(gig), Map.of("date", day.plusDays(1).toString(), "venueId", other), fan));

        assertThat(GigTime.dayOf(
                        Instant.parse(moved.path("gig").path("startsAt").asText(""))))
                .isEqualTo(day.plusDays(1));
        assertThat(moved.path("gig").path("venue").path("id").asText("")).isEqualTo(other.toString());
        assertThat(dsl.fetch("select field from catalog.gig_edit where gig_id = ?", gig)
                        .map(r -> r.get(0, String.class)))
                .containsExactlyInAnyOrder("DATE", "VENUE");
    }

    // ── which act headlined ─────────────────────────────────────────────────────────────────────

    @Test
    void someoneGoingSaysWhichActHeadlinedAndATitleThatWasTheOldHeadlinerFollows() {
        Instant at = LocalDate.now(GigTime.UK)
                .plusDays(5)
                .atTime(19, 0)
                .atZone(GigTime.UK)
                .toInstant();
        UUID gig = data.gig("KEO " + tag, venue, at, keo, tooth);
        UUID other = data.artist("Not On The Bill " + tag);
        Map<String, Object> toothHeadlines = Map.of("headlinerId", tooth);

        assertProblem(api.patch(path(gig), toothHeadlines, fan), HttpStatus.FORBIDDEN, "NOT_ATTENDING");
        ok(api.put(path(gig) + "/attendance", Map.of("status", "GOING"), fan));
        assertProblem(
                api.patch(path(gig), Map.of("headlinerId", other), fan), HttpStatus.BAD_REQUEST, "ACT_NOT_ON_BILL");

        JsonNode edited = ok(api.patch(path(gig), toothHeadlines, fan));

        assertThat(names(edited.path("lineup"))).containsExactly("Tooth " + tag, "KEO " + tag);
        assertThat(edited.path("gig").path("title").asText("")).isEqualTo("Tooth " + tag);
        assertThat(dsl.fetch(
                                "select field, old_value, new_value from catalog.gig_edit where gig_id = ?"
                                        + " order by created_at, field",
                                gig)
                        .map(r -> r.get(0) + " " + r.get(1) + " " + r.get(2)))
                .containsExactly("HEADLINER " + keo + " " + tooth, "TITLE KEO " + tag + " Tooth " + tag);

        // The same headliner again changes and logs nothing; an act added in the same edit may headline.
        ok(api.patch(path(gig), toothHeadlines, fan));
        assertThat(dsl.fetchCount(dsl.selectFrom("catalog.gig_edit").where("gig_id = ?", gig)))
                .isEqualTo(2);
        UUID late = data.artist("Late Addition " + tag);
        JsonNode both = ok(api.patch(path(gig), Map.of("addArtistIds", List.of(late), "headlinerId", late), fan));
        assertThat(names(both.path("lineup"))).containsExactly("Late Addition " + tag, "Tooth " + tag, "KEO " + tag);
    }

    // ── the acts you saw ────────────────────────────────────────────────────────────────────────

    @Test
    void aWentCountsTheWholeBillUntilTheFanUnticksAnActAndAClipOfItTicksItAgain() {
        UUID gig = pastGig(keo, tooth);
        ok(api.put(path(gig) + "/attendance", Map.of("status", "WENT"), fan));
        assertThat(timesSeen(tooth)).isEqualTo(1);
        assertThat(ok(api.get(path(gig), fan)).path("gig").path("myActsMissed").size())
                .isZero();

        JsonNode acts = ok(api.put(path(gig) + "/attendance/acts", Map.of("artistIds", List.of(keo)), fan));

        assertThat(acts.path("seen").path(0).asText("")).isEqualTo(keo.toString());
        assertThat(acts.path("missed").path(0).asText("")).isEqualTo(tooth.toString());
        assertThat(ok(api.get(path(gig), fan))
                        .path("gig")
                        .path("myActsMissed")
                        .path(0)
                        .asText(""))
                .isEqualTo(tooth.toString());
        assertThat(timesSeen(tooth)).isZero();
        assertThat(timesSeen(keo)).isEqualTo(1);
        assertThat(stats().path("totals").path("acts").asInt()).isEqualTo(1);
        assertThat(stats().path("acts").size()).isEqualTo(1);

        // Refused: no act, an act not on the bill.
        assertThat(api.put(path(gig) + "/attendance/acts", Map.of("artistIds", List.of()), fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertProblem(
                api.put(path(gig) + "/attendance/acts", Map.of("artistIds", List.of(data.artist("X " + tag))), fan),
                HttpStatus.BAD_REQUEST,
                "ACT_NOT_ON_BILL");

        // A clip of Tooth: you filmed them, so you saw them.
        created(api.post(
                "/api/v1/posts",
                Map.of(
                        "clips",
                        List.of(Map.of("mediaId", data.video(fan.id(), "READY"), "artistId", tooth)),
                        "gigId",
                        gig),
                fan));
        assertThat(ok(api.get(path(gig), fan)).path("gig").path("myActsMissed").size())
                .isZero();
        assertThat(timesSeen(tooth)).isEqualTo(1);
    }

    @Test
    void actsCanBeChosenOnlyForAGigTheFanWentTo() {
        UUID soon = data.gig("Soon " + tag, venue, Instant.now().plus(Duration.ofDays(4)), keo, tooth);
        ok(api.put(path(soon) + "/attendance", Map.of("status", "GOING"), fan));
        assertProblem(
                api.put(path(soon) + "/attendance/acts", Map.of("artistIds", List.of(keo)), fan),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "NOT_WENT");

        // A Going on a gig that is over reads as Went: choosing its acts confirms it.
        UUID over = pastGig(keo, tooth);
        dsl.execute(
                "insert into catalog.gig_attendance (user_id, gig_id, status, source, created_at, updated_at)"
                        + " values (?, ?, 'GOING', 'USER', now(), now())",
                fan.id(),
                over);
        ok(api.put(path(over) + "/attendance/acts", Map.of("artistIds", List.of(keo)), fan));
        assertThat(dsl.fetchValue(
                        "select status from catalog.gig_attendance where user_id = ? and gig_id = ?", fan.id(), over))
                .isEqualTo("WENT");
    }

    // ── a clip's act places it ──────────────────────────────────────────────────────────────────

    @Test
    void aClipIsOnItsOwnActsPagesOnlyAndAPostIsTaggedWithItsClipsActs() {
        UUID gig = pastGig(keo, tooth);
        String postId = created(api.post(
                        "/api/v1/posts",
                        Map.of(
                                "clips",
                                List.of(Map.of("mediaId", data.video(fan.id(), "READY"), "artistId", keo)),
                                "gigId",
                                gig),
                        fan))
                .path("id")
                .asText("");
        // Left out, artistIds no longer tags the whole bill.
        assertThat(dsl.fetch("select artist_id from content.post_artist where post_id = ?", UUID.fromString(postId))
                        .map(r -> r.get(0, UUID.class)))
                .containsExactly(keo);
        // A tag of the whole bill (old data) no longer puts it on Tooth's page.
        dsl.execute(
                "insert into content.post_artist (post_id, artist_id) values (?, ?)", UUID.fromString(postId), tooth);

        assertThat(ids(ok(api.get("/api/v1/artists/" + keo + "/posts", fan)))).containsExactly(postId);
        assertThat(ids(ok(api.get("/api/v1/artists/" + tooth + "/posts", fan)))).isEmpty();
        assertThat(ids(ok(api.get(path(gig) + "/posts?artistId=" + tooth, fan))))
                .isEmpty();
        assertThat(ids(ok(api.get(path(gig) + "/posts?artistId=" + keo, fan)))).containsExactly(postId);
    }

    @Test
    void theMigrationGivesAnActToClipsFromSingleActGigsAndTagsPostsWithTheirClipsActs() throws IOException {
        UUID solo = pastGig(keo);
        UUID both = data.gig("Both " + tag, venue, Instant.now().minus(Duration.ofDays(9)), keo, tooth);
        UUID soloPost = UUID.fromString(post(solo, null));
        UUID bothPost = UUID.fromString(post(both, null));
        // As V096 left older posts: no act on the clip, the whole bill as tags.
        dsl.execute("update content.post_clip set artist_id = null where post_id in (?, ?)", soloPost, bothPost);
        dsl.execute(
                "insert into content.post_artist (post_id, artist_id) values (?, ?), (?, ?), (?, ?)"
                        + " on conflict do nothing",
                soloPost,
                tooth,
                bothPost,
                keo,
                bothPost,
                tooth);

        dsl.execute(migration("V105__content_clip_acts.sql"));

        assertThat(dsl.fetchValue("select artist_id from content.post_clip where post_id = ?", soloPost))
                .isEqualTo(keo);
        assertThat(dsl.fetch("select artist_id from content.post_artist where post_id = ?", soloPost)
                        .map(r -> r.get(0, UUID.class)))
                .containsExactly(keo);
        // Two acts and no clue which: the clip waits for its author ("Which act is this?"), tags kept.
        assertThat(dsl.fetchValue("select artist_id from content.post_clip where post_id = ?", bothPost))
                .isNull();
        assertThat(dsl.fetch("select artist_id from content.post_artist where post_id = ?", bothPost)
                        .map(r -> r.get(0, UUID.class)))
                .containsExactlyInAnyOrder(keo, tooth);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private UUID pastGig(UUID... acts) {
        Instant at = LocalDate.now(GigTime.UK)
                .minusDays(3)
                .atTime(20, 0)
                .atZone(GigTime.UK)
                .toInstant();
        return data.gig("Past " + tag + " " + UUID.randomUUID(), venue, at, acts);
    }

    private String post(UUID gig, UUID act) {
        Map<String, Object> clip = act == null
                ? Map.of("mediaId", data.video(fan.id(), "READY"))
                : Map.of("mediaId", data.video(fan.id(), "READY"), "artistId", act);
        return created(api.post("/api/v1/posts", Map.of("clips", List.of(clip), "gigId", gig), fan))
                .path("id")
                .asText("");
    }

    private GigDraft draft(Instant at, List<UUID> acts) {
        return new GigDraft(
                "KEO " + tag,
                venue,
                at,
                null,
                "SCHEDULED",
                null,
                null,
                null,
                null,
                null,
                Ids.newId().toString().getBytes(),
                acts);
    }

    private List<String> lineup(UUID gig) {
        return names(ok(api.get(path(gig), fan)).path("lineup"));
    }

    private int timesSeen(UUID artistId) {
        return ok(api.get("/api/v1/users/" + fan.id() + "/stats/gigs/count?artistId=" + artistId, fan))
                .path("gigs")
                .asInt();
    }

    private JsonNode stats() {
        return ok(api.get("/api/v1/users/" + fan.id() + "/stats", fan));
    }

    private static String path(UUID gig) {
        return "/api/v1/gigs/" + gig;
    }

    private static List<String> names(JsonNode artists) {
        return artists.valueStream().map(a -> a.path("name").asText("")).toList();
    }

    private static List<String> ids(JsonNode page) {
        return page.path("items")
                .valueStream()
                .map(p -> p.path("id").asText(""))
                .toList();
    }

    private static String migration(String name) throws IOException {
        try (InputStream in = new ClassPathResource("db/migration/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(status);
        assertThat(response.getBody().path("code").asText("")).isEqualTo(code);
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static JsonNode created(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
