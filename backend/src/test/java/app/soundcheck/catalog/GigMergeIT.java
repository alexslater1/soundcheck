package app.soundcheck.catalog;

import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_ATTENDANCE;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_ATTENDANCE_MISSED;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_EDIT;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_REDIRECT;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_SOURCE;
import static app.soundcheck.shared.jooq.content.Tables.GIG_COMMENT;
import static app.soundcheck.shared.jooq.content.Tables.POST;
import static app.soundcheck.shared.jooq.ingestion.Tables.MATCH_REVIEW;
import static app.soundcheck.shared.jooq.social.Tables.GIG_ALERT_SENT;
import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.shared.Ids;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * Merging a duplicate gig moves every row that points at it, from every module, in one
 * transaction; a conflict moves nothing; the merged id keeps working through its redirect.
 */
class GigMergeIT extends IntegrationTest {

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

    @Autowired
    Clock clock;

    @Autowired
    List<GigMergeParticipant> participants;

    TestApi api;
    TestData data;
    TestApi.User admin;
    String tag;
    UUID venue;
    UUID headliner;
    UUID support;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        admin = api.admin();
        tag = UUID.randomUUID().toString().substring(0, 8);
        venue = data.venue("Band on the Wall " + tag);
        headliner = data.artist("Keo " + tag);
        support = data.artist("Opener " + tag);
    }

    @Test
    void contentSocialAndIngestionEachTakePart() {
        assertThat(participants)
                .extracting(p -> p.getClass().getSimpleName())
                .contains("ContentGigMerge", "SocialGigMerge", "MatchReviewGigMerge");
    }

    @Test
    void everyRowPointingAtTheDuplicateMovesAndTheDuplicateIsGone() {
        UUID into = pastGig(4, headliner);
        UUID from = pastGig(4, headliner, support);
        Instant startsAt = startsAt(from);

        // catalog: a mark, an act missed, a listing, an edit
        TestApi.User fan = api.register("mfan");
        attend(fan.id(), from, "WENT", "USER");
        dsl.insertInto(GIG_ATTENDANCE_MISSED)
                .set(GIG_ATTENDANCE_MISSED.USER_ID, fan.id())
                .set(GIG_ATTENDANCE_MISSED.GIG_ID, from)
                .set(GIG_ATTENDANCE_MISSED.ARTIST_ID, support)
                .execute();
        listing(from, "TEST", "dup-" + tag, false);
        dsl.insertInto(GIG_EDIT)
                .set(GIG_EDIT.ID, Ids.newId())
                .set(GIG_EDIT.GIG_ID, from)
                .set(GIG_EDIT.USER_ID, fan.id())
                .set(GIG_EDIT.FIELD, "TITLE")
                .set(GIG_EDIT.NEW_VALUE, "Keo live")
                .execute();
        // content: a post and a gig comment
        TestApi.User poster = api.register("mpost");
        String postId = created(api.post(
                        "/api/v1/posts",
                        Map.of("clips", List.of(Map.of("mediaId", data.video(poster.id(), "READY"))), "gigId", from),
                        poster))
                .path("id")
                .asText("");
        created(api.post("/api/v1/gigs/" + from + "/comments", Map.of("body", "What a night"), fan));
        // social: an alert record
        dsl.insertInto(GIG_ALERT_SENT)
                .set(GIG_ALERT_SENT.USER_ID, fan.id())
                .set(GIG_ALERT_SENT.GIG_ID, from)
                .set(GIG_ALERT_SENT.STARTS_AT, startsAt.atOffset(ZoneOffset.UTC))
                .execute();
        // ingestion: a review naming the duplicate, and one that asked whether the two are one gig
        UUID other = pastGig(4, support);
        UUID reviewOfOther = review("other-" + tag, other, from);
        UUID reviewOfPair = review("pair-" + tag, from, into);

        JsonNode result = ok(merge(from, into));

        assertThat(result.path("into").asText("")).isEqualTo(into.toString());
        // Two marks: the fan's, and the poster's Went that their post made.
        assertThat(moved(result)).containsEntry("attendance", 2L).containsEntry("posts", 1L);
        // Nothing points at the duplicate any more, and it is gone.
        assertThat(dsl.fetchExists(GIG, GIG.ID.eq(from))).isFalse();
        assertThat(dsl.fetchCount(GIG_ATTENDANCE, GIG_ATTENDANCE.GIG_ID.eq(from)))
                .isZero();
        assertThat(dsl.fetchCount(POST, POST.GIG_ID.eq(from))).isZero();
        // Every row is at the survivor.
        assertThat(status(fan.id(), into)).isEqualTo("WENT");
        assertThat(dsl.select(GIG_ATTENDANCE_MISSED.ARTIST_ID)
                        .from(GIG_ATTENDANCE_MISSED)
                        .where(GIG_ATTENDANCE_MISSED.USER_ID.eq(fan.id()))
                        .and(GIG_ATTENDANCE_MISSED.GIG_ID.eq(into))
                        .fetch(GIG_ATTENDANCE_MISSED.ARTIST_ID))
                .containsExactly(support);
        assertThat(dsl.fetchCount(GIG_SOURCE, GIG_SOURCE.GIG_ID.eq(into).and(GIG_SOURCE.EXTERNAL_ID.eq("dup-" + tag))))
                .isOne();
        assertThat(dsl.select(GIG_EDIT.FIELD)
                        .from(GIG_EDIT)
                        .where(GIG_EDIT.GIG_ID.eq(into))
                        .orderBy(GIG_EDIT.CREATED_AT)
                        .fetch(GIG_EDIT.FIELD))
                .containsExactly("TITLE", "MERGE");
        assertThat(dsl.select(POST.GIG_ID)
                        .from(POST)
                        .where(POST.ID.eq(UUID.fromString(postId)))
                        .fetchOne(POST.GIG_ID))
                .isEqualTo(into);
        assertThat(dsl.fetchCount(GIG_COMMENT, GIG_COMMENT.GIG_ID.eq(into))).isOne();
        assertThat(dsl.fetchCount(GIG_ALERT_SENT, GIG_ALERT_SENT.GIG_ID.eq(into)))
                .isOne();
        assertThat(dsl.select(MATCH_REVIEW.GIG_ID, MATCH_REVIEW.CANDIDATE_GIG_ID, MATCH_REVIEW.STATUS)
                        .from(MATCH_REVIEW)
                        .where(MATCH_REVIEW.ID.eq(reviewOfOther))
                        .fetchOne()
                        .intoList())
                .containsExactly(other, into, "OPEN");
        assertThat(dsl.select(MATCH_REVIEW.GIG_ID, MATCH_REVIEW.CANDIDATE_GIG_ID, MATCH_REVIEW.STATUS)
                        .from(MATCH_REVIEW)
                        .where(MATCH_REVIEW.ID.eq(reviewOfPair))
                        .fetchOne()
                        .intoList())
                .containsExactly(into, into, "MERGED");
        // The bill is the union: the survivor's acts first, then the duplicate's extra act.
        assertThat(dsl.select(GIG_ARTIST.ARTIST_ID)
                        .from(GIG_ARTIST)
                        .where(GIG_ARTIST.GIG_ID.eq(into))
                        .orderBy(GIG_ARTIST.BILLING_ORDER)
                        .fetch(GIG_ARTIST.ARTIST_ID))
                .containsExactly(headliner, support);
        // The moved post is still a feed candidate (the view follows the table).
        assertThat(dsl.fetchValue("select gig_id from feed.post_candidate where post_id = ?", UUID.fromString(postId)))
                .isEqualTo(into);
    }

    @Test
    void theMergedIdOpensTheSurvivorWithItsPosts() {
        UUID into = pastGig(5, headliner);
        UUID from = pastGig(5, headliner);
        TestApi.User poster = api.register("redir");
        created(api.post(
                "/api/v1/posts",
                Map.of("clips", List.of(Map.of("mediaId", data.video(poster.id(), "READY"))), "gigId", from),
                poster));
        ok(merge(from, into));

        JsonNode detail = ok(api.get("/api/v1/gigs/" + from, poster));

        assertThat(detail.path("gig").path("id").asText("")).isEqualTo(into.toString());
        assertThat(detail.path("postsCount").asLong()).isEqualTo(1);
        assertThat(dsl.fetchExists(
                        GIG_REDIRECT, GIG_REDIRECT.FROM_GIG_ID.eq(from).and(GIG_REDIRECT.MERGED_BY.eq(admin.id()))))
                .isTrue();
    }

    @Test
    void theStrongerMarkAndTheFansOwnWordWin() {
        UUID into = pastGig(6, headliner);
        UUID from = pastGig(6, headliner);
        TestApi.User went = api.register("went");
        TestApi.User user = api.register("usr");
        TestApi.User only = api.register("only");
        attend(went.id(), from, "WENT", "POST");
        attend(went.id(), into, "GOING", "USER");
        attend(user.id(), from, "GOING", "USER");
        attend(user.id(), into, "WENT", "POST");
        attend(only.id(), from, "INTERESTED", "USER");

        ok(merge(from, into));

        assertThat(mark(went.id(), into)).containsExactly("WENT", "USER");
        assertThat(mark(user.id(), into)).containsExactly("WENT", "USER");
        assertThat(mark(only.id(), into)).containsExactly("INTERESTED", "USER");
    }

    @Test
    void twoPrimaryListingsBecomeOne() {
        UUID into = pastGig(7, headliner);
        UUID from = pastGig(7, headliner);
        listing(into, "TEST", "keep-" + tag, true);
        listing(from, "TEST", "move-" + tag, true);

        ok(merge(from, into));

        assertThat(dsl.select(GIG_SOURCE.EXTERNAL_ID)
                        .from(GIG_SOURCE)
                        .where(GIG_SOURCE.GIG_ID.eq(into))
                        .and(GIG_SOURCE.PRIMARY_LISTING.isTrue())
                        .fetch(GIG_SOURCE.EXTERNAL_ID))
                .containsExactly("keep-" + tag);
        assertThat(dsl.fetchCount(GIG_SOURCE, GIG_SOURCE.GIG_ID.eq(into))).isEqualTo(2);
    }

    @Test
    void aConflictRefusesTheMergeAndMovesNothing() {
        UUID into = pastGig(8, headliner);
        UUID from = pastGig(8, headliner, support);
        TestApi.User both = api.register("both");
        for (UUID gig : List.of(from, into)) {
            created(api.post(
                    "/api/v1/posts",
                    Map.of("clips", List.of(Map.of("mediaId", data.video(both.id(), "READY"))), "gigId", gig),
                    both));
        }
        TestApi.User fan = api.register("cfan");
        attend(fan.id(), from, "INTERESTED", "USER");
        listing(from, "TEST", "c-" + tag, true);
        created(api.post("/api/v1/gigs/" + from + "/comments", Map.of("body", "Here"), fan));
        dsl.insertInto(GIG_ALERT_SENT)
                .set(GIG_ALERT_SENT.USER_ID, fan.id())
                .set(GIG_ALERT_SENT.GIG_ID, from)
                .set(GIG_ALERT_SENT.STARTS_AT, startsAt(from).atOffset(ZoneOffset.UTC))
                .execute();
        UUID pair = review("c-" + tag, from, into);
        List<Long> before = everyTable(from);

        JsonNode preview = ok(api.get("/api/v1/admin/catalog/gigs/" + from + "/merge-preview?into=" + into, admin));
        ResponseEntity<JsonNode> refused = merge(from, into);

        assertThat(preview.path("conflicts").get(0).asText("")).contains("@" + both.handle());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().path("code").asText("")).isEqualTo("MERGE_CONFLICT");
        assertThat(refused.getBody().path("detail").asText("")).contains("@" + both.handle());
        assertThat(everyTable(from)).isEqualTo(before);
        assertThat(dsl.fetchExists(GIG, GIG.ID.eq(from))).isTrue();
        assertThat(dsl.fetchExists(GIG_REDIRECT, GIG_REDIRECT.FROM_GIG_ID.eq(from)))
                .isFalse();
        assertThat(dsl.fetchCount(GIG_EDIT, GIG_EDIT.GIG_ID.eq(into).and(GIG_EDIT.FIELD.eq("MERGE"))))
                .isZero();
        assertThat(dsl.select(MATCH_REVIEW.STATUS)
                        .from(MATCH_REVIEW)
                        .where(MATCH_REVIEW.ID.eq(pair))
                        .fetchOne(0))
                .isEqualTo("OPEN");
        assertThat(dsl.fetchCount(GIG_ARTIST, GIG_ARTIST.GIG_ID.eq(into))).isOne();
    }

    @Test
    void aChainLeadsToTheLastSurvivor() {
        UUID a = pastGig(9, headliner);
        UUID b = pastGig(9, headliner);
        UUID c = pastGig(9, headliner);
        TestApi.User fan = api.register("chain");

        ok(merge(a, b));
        ok(merge(b, c));

        assertThat(ok(api.get("/api/v1/gigs/" + a, fan)).path("gig").path("id").asText(""))
                .isEqualTo(c.toString());
        assertThat(ok(api.get("/api/v1/gigs/" + b, fan)).path("gig").path("id").asText(""))
                .isEqualTo(c.toString());
        // A merged id can't be merged again; a gig can't merge into itself; an unknown id is not found.
        assertThat(code(merge(a, c))).isEqualTo("GIG_ALREADY_MERGED");
        assertThat(code(merge(c, c))).isEqualTo("MERGE_SAME_GIG");
        assertThat(code(merge(UUID.randomUUID(), c))).isEqualTo("GIG_NOT_FOUND");
    }

    @Test
    void thePreviewShowsBothGigsAndWhatWouldMove() {
        UUID into = pastGig(10, headliner);
        UUID from = pastGig(10, headliner, support);
        TestApi.User fan = api.register("prev");
        attend(fan.id(), from, "WENT", "USER");
        listing(from, "TEST", "p-" + tag, true);

        JsonNode preview = ok(api.get("/api/v1/admin/catalog/gigs/" + from + "/merge-preview?into=" + into, admin));

        assertThat(preview.path("from").path("gig").path("id").asText("")).isEqualTo(from.toString());
        assertThat(preview.path("into").path("gig").path("id").asText("")).isEqualTo(into.toString());
        assertThat(preview.path("sameDay").asBoolean()).isTrue();
        assertThat(preview.path("sameVenue").asBoolean()).isTrue();
        assertThat(preview.path("conflicts")).isEmpty();
        assertThat(preview.path("from").path("listings").get(0).path("primary").asBoolean())
                .isTrue();
        Map<String, Long> moving = new HashMap<>();
        preview.path("moving")
                .forEach(m ->
                        moving.put(m.path("what").asText(""), m.path("count").asLong()));
        assertThat(moving)
                .containsEntry("attendance", 1L)
                .containsEntry("acts", 1L) // the support; the headliner is on both bills
                .containsEntry("listings", 1L)
                .containsEntry("posts", 0L)
                .containsEntry("gigComments", 0L)
                .containsEntry("alertsSent", 0L)
                .containsEntry("matchReviews", 0L);
        // The preview changes nothing.
        assertThat(dsl.fetchExists(GIG, GIG.ID.eq(from))).isTrue();
        assertThat(api.get("/api/v1/admin/catalog/gigs/" + from + "/merge-preview?into=" + into, fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private UUID pastGig(int daysAgo, UUID... acts) {
        Instant at = LocalDate.now(clock.withZone(UK))
                .minusDays(daysAgo)
                .atTime(20, 0)
                .atZone(UK)
                .toInstant();
        return data.gig("Keo " + tag, venue, at, acts);
    }

    private Instant startsAt(UUID gig) {
        return dsl.select(GIG.STARTS_AT)
                .from(GIG)
                .where(GIG.ID.eq(gig))
                .fetchOne(GIG.STARTS_AT)
                .toInstant();
    }

    private void attend(UUID user, UUID gig, String status, String source) {
        dsl.insertInto(GIG_ATTENDANCE)
                .set(GIG_ATTENDANCE.USER_ID, user)
                .set(GIG_ATTENDANCE.GIG_ID, gig)
                .set(GIG_ATTENDANCE.STATUS, status)
                .set(GIG_ATTENDANCE.SOURCE, source)
                .execute();
    }

    private String status(UUID user, UUID gig) {
        return mark(user, gig).getFirst();
    }

    private List<String> mark(UUID user, UUID gig) {
        var row = dsl.select(GIG_ATTENDANCE.STATUS, GIG_ATTENDANCE.SOURCE)
                .from(GIG_ATTENDANCE)
                .where(GIG_ATTENDANCE.USER_ID.eq(user))
                .and(GIG_ATTENDANCE.GIG_ID.eq(gig))
                .fetchOne();
        return row == null ? List.of() : List.of(row.value1(), row.value2());
    }

    private void listing(UUID gig, String source, String externalId, boolean primary) {
        dsl.insertInto(GIG_SOURCE)
                .set(GIG_SOURCE.SOURCE, source)
                .set(GIG_SOURCE.EXTERNAL_ID, externalId)
                .set(GIG_SOURCE.GIG_ID, gig)
                .set(GIG_SOURCE.LAST_SEEN_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .set(GIG_SOURCE.PRIMARY_LISTING, primary)
                .execute();
    }

    private UUID review(String externalId, UUID gig, UUID candidate) {
        UUID id = Ids.newId();
        dsl.insertInto(MATCH_REVIEW)
                .set(MATCH_REVIEW.ID, id)
                .set(MATCH_REVIEW.SOURCE, "TEST")
                .set(MATCH_REVIEW.EXTERNAL_ID, externalId)
                .set(MATCH_REVIEW.GIG_ID, gig)
                .set(MATCH_REVIEW.CANDIDATE_GIG_ID, candidate)
                .set(MATCH_REVIEW.TITLE, "Keo")
                .set(MATCH_REVIEW.CANDIDATE_TITLE, "Keo")
                .set(MATCH_REVIEW.TITLE_SIMILARITY, 0.9f)
                .set(MATCH_REVIEW.ACT_SIMILARITY, 0.0f)
                .set(MATCH_REVIEW.REASON, "test")
                .execute();
        return id;
    }

    /** How many rows each table keeps for the gig, in a fixed order. */
    private List<Long> everyTable(UUID gig) {
        List<Long> counts = new ArrayList<>();
        counts.add((long) dsl.fetchCount(GIG_ATTENDANCE, GIG_ATTENDANCE.GIG_ID.eq(gig)));
        counts.add((long) dsl.fetchCount(GIG_ARTIST, GIG_ARTIST.GIG_ID.eq(gig)));
        counts.add((long) dsl.fetchCount(GIG_SOURCE, GIG_SOURCE.GIG_ID.eq(gig)));
        counts.add((long) dsl.fetchCount(GIG_EDIT, GIG_EDIT.GIG_ID.eq(gig)));
        counts.add((long) dsl.fetchCount(POST, POST.GIG_ID.eq(gig)));
        counts.add((long) dsl.fetchCount(GIG_COMMENT, GIG_COMMENT.GIG_ID.eq(gig)));
        counts.add((long) dsl.fetchCount(GIG_ALERT_SENT, GIG_ALERT_SENT.GIG_ID.eq(gig)));
        counts.add((long)
                dsl.fetchCount(MATCH_REVIEW, MATCH_REVIEW.GIG_ID.eq(gig).or(MATCH_REVIEW.CANDIDATE_GIG_ID.eq(gig))));
        return counts;
    }

    private ResponseEntity<JsonNode> merge(UUID from, UUID into) {
        return api.post("/api/v1/admin/catalog/gigs/" + from + "/merge", Map.of("into", into), admin);
    }

    private static Map<String, Long> moved(JsonNode result) {
        Map<String, Long> counts = new HashMap<>();
        result.path("moved")
                .forEach(m ->
                        counts.put(m.path("what").asText(""), m.path("count").asLong()));
        return counts;
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

    private static String code(ResponseEntity<JsonNode> response) {
        return response.getBody().path("code").asText("");
    }
}
