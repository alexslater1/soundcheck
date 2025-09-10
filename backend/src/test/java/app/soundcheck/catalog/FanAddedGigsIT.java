package app.soundcheck.catalog;

import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_ATTENDANCE;
import static org.assertj.core.api.Assertions.assertThat;

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

/** The console's queue of gigs fans added, its duplicate hints, review and delete. */
class FanAddedGigsIT extends IntegrationTest {

    static final ZoneId UK = ZoneId.of("Europe/London");
    static final String QUEUE = "/api/v1/admin/catalog/gigs/fan-added";

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
    Clock clock;

    TestApi api;
    TestData data;
    TestApi.User admin;
    TestApi.User fan;
    String tag;
    UUID venue;
    UUID act;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        admin = api.admin();
        fan = api.register("adder");
        tag = UUID.randomUUID().toString().substring(0, 8);
        venue = data.venue("Night & Day " + tag);
        act = data.artist("Keo " + tag);
    }

    @Test
    void theQueueListsFanGigsWithTheirCountsAndLikelyDuplicates() {
        Instant night = night(3);
        UUID listed = data.gig("Keo " + tag, venue, night.minus(Duration.ofHours(1)), act);
        UUID sameActElsewhere = data.gig("Keo " + tag, data.venue("Elsewhere " + tag), night, act);
        UUID sameVenueOtherNight = data.gig("Other " + tag, venue, night(4), act);
        UUID added = fanGig(night, act);
        dsl.insertInto(GIG_ATTENDANCE)
                .set(GIG_ATTENDANCE.USER_ID, fan.id())
                .set(GIG_ATTENDANCE.GIG_ID, added)
                .set(GIG_ATTENDANCE.STATUS, "WENT")
                .execute();
        created(api.post(
                "/api/v1/posts",
                Map.of("clips", List.of(Map.of("mediaId", data.video(fan.id(), "READY"))), "gigId", added),
                fan));

        JsonNode row = find(ok(api.get(QUEUE + "?limit=50", admin)), added);

        assertThat(row).as("the new fan gig is on the first page").isNotNull();
        assertThat(row.path("addedBy").asText("")).isEqualTo(fan.id().toString());
        assertThat(row.path("addedAt").asText("")).isNotBlank();
        assertThat(row.path("reviewedAt").isNull()).isTrue();
        assertThat(row.path("attendance").asLong()).isEqualTo(1); // the fan's mark (the post's Went is the same row)
        assertThat(row.path("posts").asLong()).isEqualTo(1);
        assertThat(ids(row.path("likelyDuplicates")))
                .containsExactly(listed.toString(), sameActElsewhere.toString()) // same venue first
                .doesNotContain(sameVenueOtherNight.toString());
    }

    @Test
    void aReviewedGigLeavesTheQueueForTheArchive() {
        UUID added = fanGig(night(5), act);

        ResponseEntity<JsonNode> reviewed = api.post(QUEUE.replace("fan-added", added + "/review"), Map.of(), admin);

        assertThat(reviewed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(find(ok(api.get(QUEUE + "?limit=50", admin)), added)).isNull();
        JsonNode row = find(ok(api.get(QUEUE + "?reviewed=true&limit=50", admin)), added);
        assertThat(row.path("reviewedBy").asText("")).isEqualTo(admin.id().toString());
        assertThat(row.path("reviewedAt").asText("")).isNotBlank();

        UUID listed = data.gig("Listed " + tag, venue, night(5), act);
        assertThat(code(api.post("/api/v1/admin/catalog/gigs/" + listed + "/review", Map.of(), admin)))
                .isEqualTo("GIG_NOT_FAN_ADDED");
        assertThat(code(api.post("/api/v1/admin/catalog/gigs/" + UUID.randomUUID() + "/review", Map.of(), admin)))
                .isEqualTo("GIG_NOT_FOUND");
    }

    @Test
    void aGigWithPostsOrCommentsCantBeDeleted() {
        UUID withPost = fanGig(night(6), act);
        created(api.post(
                "/api/v1/posts",
                Map.of("clips", List.of(Map.of("mediaId", data.video(fan.id(), "READY"))), "gigId", withPost),
                fan));
        UUID withComment = fanGig(night(6), act);
        created(api.post("/api/v1/gigs/" + withComment + "/comments", Map.of("body", "Anyone else?"), fan));

        ResponseEntity<JsonNode> posts = api.delete("/api/v1/admin/catalog/gigs/" + withPost, admin);
        ResponseEntity<JsonNode> comments = api.delete("/api/v1/admin/catalog/gigs/" + withComment, admin);

        assertThat(posts.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(posts)).isEqualTo("GIG_HAS_POSTS");
        assertThat(code(comments)).isEqualTo("GIG_HAS_COMMENTS");
        assertThat(dsl.fetchCount(GIG, GIG.ID.in(withPost, withComment))).isEqualTo(2);
    }

    @Test
    void aGigNothingHangsOffIsDeleted() {
        UUID added = fanGig(night(7), act);
        dsl.insertInto(GIG_ATTENDANCE)
                .set(GIG_ATTENDANCE.USER_ID, fan.id())
                .set(GIG_ATTENDANCE.GIG_ID, added)
                .set(GIG_ATTENDANCE.STATUS, "INTERESTED")
                .execute();

        ResponseEntity<JsonNode> deleted = api.delete("/api/v1/admin/catalog/gigs/" + added, admin);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dsl.fetchExists(GIG, GIG.ID.eq(added))).isFalse();
        assertThat(code(api.delete("/api/v1/admin/catalog/gigs/" + added, admin)))
                .isEqualTo("GIG_NOT_FOUND");
        assertThat(api.delete("/api/v1/admin/catalog/gigs/" + added, fan).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private UUID fanGig(Instant startsAt, UUID... acts) {
        UUID gig = data.gig("Keo " + tag, venue, startsAt, acts);
        dsl.update(GIG).set(GIG.CREATED_BY, fan.id()).where(GIG.ID.eq(gig)).execute();
        return gig;
    }

    private Instant night(int daysAgo) {
        return LocalDate.now(clock.withZone(UK))
                .minusDays(daysAgo)
                .atTime(20, 0)
                .atZone(UK)
                .toInstant();
    }

    private static JsonNode find(JsonNode page, UUID gigId) {
        for (JsonNode item : page.path("items")) {
            if (item.path("gig").path("id").asText("").equals(gigId.toString())) {
                return item;
            }
        }
        return null;
    }

    private static List<String> ids(JsonNode gigs) {
        List<String> ids = new ArrayList<>();
        gigs.forEach(g -> ids.add(g.path("id").asText("")));
        return ids;
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static void created(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.CREATED);
    }

    private static String code(ResponseEntity<JsonNode> response) {
        return response.getBody().path("code").asText("");
    }
}
