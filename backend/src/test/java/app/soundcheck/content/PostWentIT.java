package app.soundcheck.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.soundcheck.catalog.CatalogIngest;
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
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Over HTTP against real PostGIS: a post going live from a gig marks its author WENT
 * (source POST), so it counts in their stats; deleting their last live post of the gig removes a WENT
 * that only posts made, never one they set themselves; a gig whose day has not come cannot be posted
 * to; and the backfill (V078) marks what the rule would have for posts made before it.
 */
class PostWentIT extends IntegrationTest {

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
    UUID venue;
    UUID act;
    String tag;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        fan = api.register("went");
        tag = UUID.randomUUID().toString().substring(0, 8);
        venue = data.venue("Scala " + tag);
        act = data.artist("Keo " + tag);
    }

    @Test
    void aLivePostMarksItsAuthorWentAndCountsInTheirStats() {
        UUID gig = pastGig(10);
        assertThat(attendance(gig)).isNull();

        post(gig, "READY");

        assertThat(attendance(gig)).isEqualTo("WENT");
        assertThat(source(gig)).isEqualTo("POST");
        assertThat(api.get("/api/v1/users/" + fan.id() + "/stats", fan)
                        .getBody()
                        .path("totals")
                        .path("gigs")
                        .asInt())
                .isEqualTo(1);
    }

    @Test
    void aPostWaitingForItsClipMarksNothingUntilItGoesLive() {
        UUID gig = pastGig(3);
        UUID clip = data.video(fan.id(), "PROCESSING");
        String postId = created(
                        api.post("/api/v1/posts", Map.of("clips", List.of(Map.of("mediaId", clip)), "gigId", gig), fan))
                .path("id")
                .asText("");
        assertThat(attendance(gig)).as("pending: not live yet").isNull();

        data.workerFinishes(clip, fan.id());

        await().atMost(Duration.ofSeconds(10)).until(() -> status(postId).equals("PUBLISHED"));
        assertThat(attendance(gig)).isEqualTo("WENT");
        assertThat(source(gig)).isEqualTo("POST");
    }

    @Test
    void aFailedClipNeverMarksAnything() {
        UUID gig = pastGig(3);
        UUID clip = data.video(fan.id(), "PROCESSING");
        String postId = created(
                        api.post("/api/v1/posts", Map.of("clips", List.of(Map.of("mediaId", clip)), "gigId", gig), fan))
                .path("id")
                .asText("");

        data.workerGivesUp(clip, fan.id(), false);

        await().atMost(Duration.ofSeconds(10)).until(() -> status(postId).equals("FAILED"));
        assertThat(attendance(gig)).isNull();
    }

    @Test
    void goingOrInterestedBecomesWentAndDeletingTheLastPostRemovesIt() {
        UUID going = pastGig(5);
        UUID interested = pastGig(6);
        mark(going, "GOING");
        mark(interested, "INTERESTED");

        // Two clips from one gig make one post.
        String first = post(going, "READY");
        String second = post(going, "READY");
        assertThat(second).isEqualTo(first);
        post(interested, "READY");
        assertThat(attendance(going)).isEqualTo("WENT");
        assertThat(attendance(interested)).isEqualTo("WENT");
        assertThat(source(going)).isEqualTo("POST");

        // One live clip of the gig is left: still WENT.
        String clip = api.get("/api/v1/posts/" + first, fan)
                .getBody()
                .path("clips")
                .get(0)
                .path("id")
                .asText("");
        assertThat(api.delete("/api/v1/posts/" + first + "/clips/" + clip, fan).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(attendance(going)).isEqualTo("WENT");
        // The post with the last one: the mark only posts made is gone (the Going before it is not restored).
        delete(first);
        assertThat(attendance(going)).isNull();
        assertThat(attendance(interested)).as("another gig's mark is untouched").isEqualTo("WENT");
    }

    @Test
    void aWentTheFanSetThemselvesStaysAfterTheirPostsAreDeleted() {
        UUID before = pastGig(4);
        UUID after = pastGig(8);
        // Marked Went before posting: the post leaves it as the fan's own.
        mark(before, "WENT");
        String one = post(before, "READY");
        assertThat(source(before)).isEqualTo("USER");
        // Posted first, then tapped Went: the fan's tap makes the mark theirs.
        String two = post(after, "READY");
        assertThat(source(after)).isEqualTo("POST");
        mark(after, "WENT");
        assertThat(source(after)).isEqualTo("USER");

        delete(one);
        delete(two);

        assertThat(attendance(before)).isEqualTo("WENT");
        assertThat(attendance(after)).isEqualTo("WENT");
    }

    @Test
    void choosingGoingAfterAPostMadeTheMarkKeepsTheFansChoice() {
        UUID gig = pastGig(2);
        String postId = post(gig, "READY");
        mark(gig, "GOING");
        assertThat(source(gig)).isEqualTo("USER");

        delete(postId);

        assertThat(attendance(gig)).isEqualTo("GOING");
    }

    @Test
    void anotherFansPostsAndMarksAreTheirOwn() {
        TestApi.User friend = api.register("friend");
        UUID gig = pastGig(7);
        String mine = post(gig, "READY");
        String theirs = created(api.post(
                        "/api/v1/posts",
                        Map.of("clips", List.of(Map.of("mediaId", data.video(friend.id(), "READY"))), "gigId", gig),
                        friend))
                .path("id")
                .asText("");

        delete(mine);

        assertThat(attendance(gig)).isNull();
        assertThat(attendanceOf(friend, gig)).isEqualTo("WENT");
        assertThat(theirs).isNotBlank();
    }

    @Test
    void aGigWhoseDayHasNotComeCannotBePostedTo() {
        LocalDate tomorrow = LocalDate.now(UK).plusDays(1);
        UUID later =
                data.gig("Keo " + tag, venue, tomorrow.atTime(1, 0).atZone(UK).toInstant(), act);
        UUID clip = data.video(fan.id(), "READY");

        ResponseEntity<JsonNode> refused =
                api.post("/api/v1/posts", Map.of("clips", List.of(Map.of("mediaId", clip)), "gigId", later), fan);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().path("code").asText("")).isEqualTo("GIG_NOT_HAPPENED");
        assertThat(attendance(later)).isNull();
        // The clip was not used up: it can still be posted, to a gig that has happened.
        created(api.post("/api/v1/posts", Map.of("clips", List.of(Map.of("mediaId", clip)), "gigId", pastGig(1)), fan));
    }

    @Test
    void aGigThatHasStartedCanBePostedTo() {
        UUID tonight = data.gig("Keo " + tag, venue, Instant.now().minus(Duration.ofMinutes(5)), act);

        post(tonight, "READY");

        assertThat(attendance(tonight)).isEqualTo("WENT");
    }

    @Test
    void theBackfillMarksWhatTheRuleWouldHaveForOlderPosts() throws IOException {
        UUID plain = pastGig(20);
        UUID going = pastGig(21);
        UUID ownWent = pastGig(22);
        UUID deleted = pastGig(23);
        UUID later = data.gig("Keo " + tag, venue, Instant.now().plus(Duration.ofDays(9)), act);
        post(plain, "READY");
        post(going, "READY");
        post(ownWent, "READY");
        delete(post(deleted, "READY"));
        // A post of a gig still to come, from before posting one was refused.
        String early = post(pastGig(24), "READY");
        dsl.execute("update content.post set gig_id = ? where id = ?", later, UUID.fromString(early));
        // The state before the rule: no marks from posts; a Going and a Went the fan set.
        dsl.execute("delete from catalog.gig_attendance where user_id = ?", fan.id());
        mark(going, "GOING");
        mark(ownWent, "WENT");

        dsl.execute(backfill());

        assertThat(attendance(plain)).isEqualTo("WENT");
        assertThat(source(plain)).isEqualTo("POST");
        assertThat(attendance(going)).isEqualTo("WENT");
        assertThat(source(going)).isEqualTo("POST");
        assertThat(source(ownWent)).as("the fan's own Went stays theirs").isEqualTo("USER");
        assertThat(attendance(deleted)).isNull();
        assertThat(attendance(later)).isNull();

        // Running it again changes nothing.
        dsl.execute(backfill());
        assertThat(dsl.fetchCount(dsl.selectFrom("catalog.gig_attendance").where("user_id = ?", fan.id())))
                .isEqualTo(3);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /** A gig with Keo on the bill, {@code daysAgo} days ago at 20:00 UK time. */
    private UUID pastGig(int daysAgo) {
        Instant at =
                LocalDate.now(UK).minusDays(daysAgo).atTime(20, 0).atZone(UK).toInstant();
        return data.gig("Keo " + tag + " " + daysAgo, venue, at, act);
    }

    /** The fan's post of a clip in the given media status at the gig; its id. */
    private String post(UUID gig, String clipStatus) {
        return created(api.post(
                        "/api/v1/posts",
                        Map.of("clips", List.of(Map.of("mediaId", data.video(fan.id(), clipStatus))), "gigId", gig),
                        fan))
                .path("id")
                .asText("");
    }

    private void delete(String postId) {
        assertThat(api.call(HttpMethod.DELETE, "/api/v1/posts/" + postId, null, fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    private void mark(UUID gig, String status) {
        assertThat(api.put("/api/v1/gigs/" + gig + "/attendance", Map.of("status", status), fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** The fan's attendance as the gig page shows it; null when unmarked. */
    private @Nullable String attendance(UUID gig) {
        return attendanceOf(fan, gig);
    }

    private @Nullable String attendanceOf(TestApi.User user, UUID gig) {
        JsonNode mine =
                api.get("/api/v1/gigs/" + gig, user).getBody().path("gig").path("myAttendance");
        return mine.isNull() || mine.isMissingNode() ? null : mine.asText("");
    }

    private @Nullable String source(UUID gig) {
        Object source = dsl.fetchValue(
                "select source from catalog.gig_attendance where user_id = ? and gig_id = ?", fan.id(), gig);
        return source == null ? null : source.toString();
    }

    private String status(String postId) {
        return String.valueOf(dsl.fetchValue("select status from content.post where id = ?", UUID.fromString(postId)));
    }

    private static String backfill() throws IOException {
        try (InputStream in =
                new ClassPathResource("db/migration/V078__catalog_went_from_posts.sql").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static JsonNode created(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
