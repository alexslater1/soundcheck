package app.soundcheck.ingestion.internal.resolve;

import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_REDIRECT;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_SOURCE;
import static app.soundcheck.shared.jooq.ingestion.Tables.MATCH_REVIEW;
import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.ingestion.internal.RunStats;
import app.soundcheck.ingestion.internal.source.SourceArtist;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceVenue;
import app.soundcheck.ingestion.internal.source.TimePrecision;
import app.soundcheck.shared.jooq.ingestion.tables.records.MatchReviewRecord;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * The operator decides the resolver's "same gig?" questions. MERGE is the gig merge
 * of the listing's gig into the one that was there first; KEEP_APART only answers the question;
 * a merge conflict leaves it OPEN. A linked listing is never matched again, so neither decision has to be
 * remembered by the resolver.
 */
class MatchReviewDecisionsIT extends IntegrationTest {

    static final ZoneId UK = ZoneId.of("Europe/London");
    static final Instant SEEN = Instant.parse("2026-10-02T05:10:00Z");

    @Autowired
    EntityResolver resolver;

    @Autowired
    DSLContext dsl;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    app.soundcheck.catalog.CatalogIngest catalog;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    Clock clock;

    TestApi api;
    TestApi.User admin;
    String tag;
    SourceVenue tmVenue;
    SourceVenue siteVenue;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        admin = api.admin();
        tag = UUID.randomUUID().toString().substring(0, 6);
        double lat = 57.0 + Math.random(); // a spot of its own, so venue matching sees only this test's venue
        tmVenue = new SourceVenue("tmv-" + tag, "The Lamp " + tag, null, "London", null, lat, -1.5, "Europe/London");
        siteVenue = new SourceVenue("lamp-" + tag, "Lamp " + tag, null, "London", null, lat, -1.5, "Europe/London");
    }

    @Test
    void theQueueShowsBothGigsAndTheResolverNeverAsksAboutALinkedListingAgain() {
        Pair pair = almostMatching(LocalDate.of(2026, 11, 14));

        JsonNode page = ok(api.get("/api/v1/admin/ingestion/match-reviews?status=OPEN&limit=50", admin));
        JsonNode item = find(page, pair.review().getId());
        RunStats second = new RunStats();
        resolver.ingest(pair.site(), second, SEEN.plusSeconds(86_400));

        assertThat(item.path("gig").path("id").asText(""))
                .isEqualTo(pair.siteGig().toString());
        assertThat(item.path("candidate").path("id").asText(""))
                .isEqualTo(pair.tmGig().toString());
        assertThat(item.path("candidate").path("headliner").path("name").asText(""))
                .isEqualTo("Pale Kite " + tag);
        assertThat(item.path("candidate").path("venue").path("name").asText("")).isEqualTo("The Lamp " + tag);
        assertThat(item.path("titleSimilarity").asDouble()).isPositive();
        assertThat(item.path("reason").asText("")).isNotBlank();
        // The second run finds the listing by its gig_source link and asks nothing new.
        assertThat(second.flaggedForReview).isZero();
        assertThat(reviewsOf(pair.site())).hasSize(1);
    }

    @Test
    void keepApartAnswersTheQuestionAndTheNextRunLeavesItAnswered() {
        Pair pair = almostMatching(LocalDate.of(2026, 11, 15));

        assertThat(decide(pair.review().getId(), "KEEP_APART").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        RunStats next = new RunStats();
        resolver.ingest(pair.site(), next, SEEN.plusSeconds(86_400));
        ResponseEntity<JsonNode> again = decide(pair.review().getId(), "MERGE");

        MatchReviewRecord review = reviewsOf(pair.site()).getFirst();
        assertThat(review.getStatus()).isEqualTo("KEPT_APART");
        assertThat(review.getDecidedAt()).isNotNull();
        assertThat(next.flaggedForReview).isZero();
        assertThat(reviewsOf(pair.site())).hasSize(1);
        assertThat(dsl.fetchExists(GIG, GIG.ID.eq(pair.siteGig()))).isTrue();
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().path("code").asText("")).isEqualTo("REVIEW_DECIDED");
        assertThat(find(
                                ok(api.get("/api/v1/admin/ingestion/match-reviews?status=KEPT_APART&limit=50", admin)),
                                review.getId())
                        .path("status")
                        .asText(""))
                .isEqualTo("KEPT_APART");
    }

    @Test
    void mergeMovesTheListingsGigIntoTheExistingOneAndTheNextRunLandsThere() {
        Pair pair = almostMatching(LocalDate.of(2026, 11, 16));

        assertThat(decide(pair.review().getId(), "MERGE").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        RunStats next = new RunStats();
        EntityResolver.Outcome outcome = resolver.ingest(pair.site(), next, SEEN.plusSeconds(86_400));

        MatchReviewRecord review = reviewsOf(pair.site()).getFirst();
        assertThat(review.getStatus()).isEqualTo("MERGED");
        assertThat(review.getDecidedAt()).isNotNull();
        assertThat(review.getGigId()).isEqualTo(pair.tmGig());
        assertThat(dsl.fetchExists(GIG, GIG.ID.eq(pair.siteGig()))).isFalse();
        assertThat(dsl.select(GIG_REDIRECT.INTO_GIG_ID)
                        .from(GIG_REDIRECT)
                        .where(GIG_REDIRECT.FROM_GIG_ID.eq(pair.siteGig()))
                        .fetchOne(GIG_REDIRECT.INTO_GIG_ID))
                .isEqualTo(pair.tmGig());
        assertThat(gigOf(pair.site())).isEqualTo(pair.tmGig());
        assertThat(outcome).isNotEqualTo(EntityResolver.Outcome.CREATED);
        assertThat(next.flaggedForReview).isZero();
        assertThat(reviewsOf(pair.site())).hasSize(1);
    }

    @Test
    void aMergeConflictLeavesTheReviewOpen() {
        LocalDate day = LocalDate.now(clock.withZone(UK)).minusDays(3);
        Pair pair = almostMatching(day);
        // The same fan posted at both gigs that day: merging would give them two open posts at one gig.
        TestApi.User both = api.register("mrboth");
        TestData data = new TestData(catalog, dsl, tx, events);
        for (UUID gig : List.of(pair.siteGig(), pair.tmGig())) {
            assertThat(api.post(
                                    "/api/v1/posts",
                                    Map.of(
                                            "clips",
                                            List.of(Map.of("mediaId", data.video(both.id(), "READY"))),
                                            "gigId",
                                            gig),
                                    both)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<JsonNode> refused = decide(pair.review().getId(), "MERGE");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().path("code").asText("")).isEqualTo("MERGE_CONFLICT");
        MatchReviewRecord review = reviewsOf(pair.site()).getFirst();
        assertThat(review.getStatus()).isEqualTo("OPEN");
        assertThat(review.getDecidedAt()).isNull();
        assertThat(dsl.fetchExists(GIG, GIG.ID.eq(pair.siteGig()))).isTrue();
    }

    @Test
    void aReviewWhoseGigHasGoneLeavesTheQueue() {
        Pair pair = almostMatching(LocalDate.of(2026, 11, 17));
        dsl.deleteFrom(GIG_SOURCE).where(GIG_SOURCE.GIG_ID.eq(pair.siteGig())).execute();
        dsl.deleteFrom(GIG).where(GIG.ID.eq(pair.siteGig())).execute();

        JsonNode page = ok(api.get("/api/v1/admin/ingestion/match-reviews?status=OPEN&limit=50", admin));

        List<String> ids = new java.util.ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asText("")));
        assertThat(ids).doesNotContain(pair.review().getId().toString());
    }

    @Test
    void onlyKnownStatusesAndOpenReviewsAreAccepted() {
        assertThat(api.get("/api/v1/admin/ingestion/match-reviews?status=MAYBE", admin)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(decide(UUID.randomUUID(), "KEEP_APART").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    record Pair(UUID tmGig, UUID siteGig, SourceGig site, MatchReviewRecord review) {}

    /**
     * Ticketmaster lists "Pale Kite" at 20:00; the venue's site lists "Pale Kites" with doors at 19:00 the
     * same night. Close, not convincing: two gigs and one review (as VenueListingMatchingIT shows).
     */
    private Pair almostMatching(LocalDate day) {
        Instant at = day.atTime(20, 0).atZone(UK).toInstant();
        SourceGig tm = new SourceGig(
                "TICKETMASTER",
                "tm-pk-" + tag,
                "https://tickets.example.com/pk",
                "Pale Kite " + tag,
                at,
                null,
                "SCHEDULED",
                "https://tickets.example.com/pk",
                null,
                null,
                null,
                null,
                tmVenue,
                List.of(new SourceArtist("tma-pk-" + tag, "Pale Kite " + tag, null, List.of(), null)));
        assertThat(resolver.ingest(tm, new RunStats(), SEEN)).isEqualTo(EntityResolver.Outcome.CREATED);
        String title = "Pale Kites " + tag;
        SourceGig site = new SourceGig(
                "VENUE",
                siteVenue.externalId() + ":ev-pk",
                "https://lamp.example.com/events/pk",
                title,
                at.minusSeconds(3_600),
                null,
                "SCHEDULED",
                null,
                null,
                null,
                null,
                null,
                siteVenue,
                TitleParser.parse(title).acts().stream()
                        .map(name -> new SourceArtist("", name, null, List.of(), null))
                        .toList(),
                TimePrecision.DOORS);
        RunStats stats = new RunStats();
        assertThat(resolver.ingest(site, stats, SEEN)).isEqualTo(EntityResolver.Outcome.CREATED);
        assertThat(stats.flaggedForReview).isEqualTo(1);
        return new Pair(gigOf(tm), gigOf(site), site, reviewsOf(site).getFirst());
    }

    private List<MatchReviewRecord> reviewsOf(SourceGig listing) {
        return dsl.selectFrom(MATCH_REVIEW)
                .where(MATCH_REVIEW.SOURCE.eq(listing.source()))
                .and(MATCH_REVIEW.EXTERNAL_ID.eq(listing.externalId()))
                .fetch();
    }

    private UUID gigOf(SourceGig listing) {
        return dsl.select(GIG_SOURCE.GIG_ID)
                .from(GIG_SOURCE)
                .where(GIG_SOURCE.SOURCE.eq(listing.source()))
                .and(GIG_SOURCE.EXTERNAL_ID.eq(listing.externalId()))
                .fetchSingle(GIG_SOURCE.GIG_ID);
    }

    private ResponseEntity<JsonNode> decide(UUID review, String decision) {
        return api.post(
                "/api/v1/admin/ingestion/match-reviews/" + review + "/decide", Map.of("decision", decision), admin);
    }

    private static JsonNode find(JsonNode page, UUID id) {
        for (JsonNode item : page.path("items")) {
            if (item.path("id").asText("").equals(id.toString())) {
                return item;
            }
        }
        throw new AssertionError("review " + id + " not in " + page);
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
