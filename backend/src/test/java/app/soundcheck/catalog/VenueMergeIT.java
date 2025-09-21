package app.soundcheck.catalog;

import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.SAVED_GIG_FILTER;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE_ALIAS;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE_EXTERNAL_ID;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE_REDIRECT;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE_SIBLING;
import static app.soundcheck.shared.jooq.social.Tables.VENUE_FOLLOW;
import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.ingestion.internal.RunStats;
import app.soundcheck.ingestion.internal.resolve.EntityResolver;
import app.soundcheck.ingestion.internal.source.SourceArtist;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceVenue;
import app.soundcheck.shared.Ids;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * Merging a duplicate venue moves every reference to it in one transaction, deletes it,
 * and leaves a redirect (its id) and an alias (its name), so links, the sources' next runs and fans adding
 * gigs all land on the survivor.
 */
class VenueMergeIT extends IntegrationTest {

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
    EntityResolver resolver;

    @Autowired
    List<VenueMergeParticipant> participants;

    TestApi api;
    TestData data;
    TestApi.User admin;
    String tag;
    double lat;
    double lng;
    UUID from;
    UUID into;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        admin = api.admin();
        tag = UUID.randomUUID().toString().substring(0, 8);
        lat = 55.0 + Math.random(); // a place of its own: only this test's venues are near it
        lng = -4.0 + Math.random() / 2;
        from = data.venue("Rumble Hall " + tag, lat, lng);
        into = data.venue("Corn Exchange " + tag, lat + 0.0005, lng);
    }

    @Test
    void socialTakesPart() {
        assertThat(participants).extracting(p -> p.getClass().getSimpleName()).contains("SocialVenueMerge");
    }

    @Test
    void everyReferenceMovesAndTheDuplicateIsGone() {
        UUID act = data.artist("Merge Act " + tag);
        Instant night = LocalDate.now(UK).plusDays(10).atTime(20, 0).atZone(UK).toInstant();
        UUID gigThere = data.gig("Merge Act " + tag, from, night, act);
        UUID gigLater = data.gig("Merge Act " + tag, from, night.plusSeconds(7 * 86_400), act);
        UUID gigHere = data.gig("Merge Act " + tag, into, night.plusSeconds(3_600), act);
        tx.executeWithoutResult(s -> {
            catalogIngest.linkVenue("TICKETMASTER", "tmv-" + tag, from);
            catalogIngest.linkSiblings(from, into);
        });
        UUID room = data.venue("Rumble Hall Upstairs " + tag, lat, lng);
        tx.executeWithoutResult(s -> catalogIngest.linkSiblings(from, room));
        TestApi.User early = api.register("vfe");
        TestApi.User fan = api.register("vff");
        follow(early.id(), from, OffsetDateTime.now(ZoneOffset.UTC).minusDays(30), "ALL");
        follow(early.id(), into, OffsetDateTime.now(ZoneOffset.UTC), "NONE");
        follow(fan.id(), from, OffsetDateTime.now(ZoneOffset.UTC), "GIGS");
        UUID other = data.venue("Elsewhere " + tag, lat - 0.5, lng);
        UUID search = savedSearch(early.id(), from, other, into);

        JsonNode preview = ok(api.get("/api/v1/admin/catalog/venues/" + from + "/merge-preview?into=" + into, admin));
        JsonNode result = ok(merge(from, into));

        assertThat(preview.path("conflicts")).isEmpty();
        assertThat(preview.path("distanceMetres").asLong()).isBetween(40L, 70L);
        assertThat(counts(preview.path("moving")))
                .containsEntry("gigs", 2L)
                .containsEntry("listings", 1L)
                .containsEntry("followers", 2L)
                .containsEntry("savedSearches", 1L);
        assertThat(counts(result.path("moved"))).containsEntry("gigs", 2L);
        // The same night at the survivor: listed, not merged.
        assertThat(result.path("sameNight")).hasSize(1);
        assertThat(result.path("sameNight").get(0).path("movedGigId").asText(""))
                .isEqualTo(gigThere.toString());
        assertThat(result.path("sameNight").get(0).path("gigId").asText("")).isEqualTo(gigHere.toString());

        // catalog: gigs (with the survivor's point), the source's id, siblings, saved searches
        assertThat(dsl.fetchCount(GIG, GIG.VENUE_ID.eq(into))).isEqualTo(3);
        assertThat(dsl.fetchExists(GIG, GIG.ID.eq(gigLater))).isTrue();
        assertThat(dsl.fetchValue("select ST_Y(location::geometry) from catalog.gig where id = ?", gigThere))
                .isEqualTo(dsl.fetchValue("select ST_Y(location::geometry) from catalog.venue where id = ?", into));
        assertThat(dsl.select(VENUE_EXTERNAL_ID.VENUE_ID)
                        .from(VENUE_EXTERNAL_ID)
                        .where(VENUE_EXTERNAL_ID.SOURCE.eq("TICKETMASTER"))
                        .and(VENUE_EXTERNAL_ID.EXTERNAL_ID.eq("tmv-" + tag))
                        .fetchOne(VENUE_EXTERNAL_ID.VENUE_ID))
                .isEqualTo(into);
        assertThat(dsl.select(VENUE_SIBLING.SIBLING_ID)
                        .from(VENUE_SIBLING)
                        .where(VENUE_SIBLING.VENUE_ID.eq(into))
                        .fetch(VENUE_SIBLING.SIBLING_ID))
                .containsExactly(room);
        assertThat(dsl.fetchCount(VENUE_SIBLING, VENUE_SIBLING.SIBLING_ID.eq(into)))
                .isOne();
        assertThat(dsl.select(SAVED_GIG_FILTER.VENUE_IDS)
                        .from(SAVED_GIG_FILTER)
                        .where(SAVED_GIG_FILTER.ID.eq(search))
                        .fetchOne(SAVED_GIG_FILTER.VENUE_IDS))
                .containsExactly(into, other);
        // social: the fan who followed both keeps the earlier follow, with its level
        assertThat(dsl.select(VENUE_FOLLOW.NOTIFY)
                        .from(VENUE_FOLLOW)
                        .where(VENUE_FOLLOW.USER_ID.eq(early.id()))
                        .fetch(VENUE_FOLLOW.NOTIFY))
                .containsExactly("ALL");
        assertThat(dsl.fetchExists(
                        VENUE_FOLLOW, VENUE_FOLLOW.USER_ID.eq(fan.id()).and(VENUE_FOLLOW.VENUE_ID.eq(into))))
                .isTrue();
        // gone, with a redirect and an alias
        assertThat(dsl.fetchExists(VENUE, VENUE.ID.eq(from))).isFalse();
        assertThat(dsl.fetchExists(
                        VENUE_REDIRECT,
                        VENUE_REDIRECT.FROM_VENUE_ID.eq(from).and(VENUE_REDIRECT.INTO_VENUE_ID.eq(into))))
                .isTrue();
        assertThat(dsl.select(VENUE_ALIAS.VENUE_ID)
                        .from(VENUE_ALIAS)
                        .where(VENUE_ALIAS.NAME_NORM.eq(NameNormaliser.normalise("Rumble Hall " + tag)))
                        .fetchOne(VENUE_ALIAS.VENUE_ID))
                .isEqualTo(into);
    }

    @Test
    void theOldIdOpensTheSurvivor() {
        UUID act = data.artist("Link Act " + tag);
        data.gig(
                "Link Act " + tag,
                from,
                LocalDate.now(UK).plusDays(5).atTime(20, 0).atZone(UK).toInstant(),
                act);
        ok(merge(from, into));

        TestApi.User fan = api.register("vlink");
        JsonNode venue = ok(api.get("/api/v1/venues/" + from, fan));
        JsonNode gigs = ok(api.get("/api/v1/venues/" + from + "/gigs", fan));

        assertThat(venue.path("id").asText("")).isEqualTo(into.toString());
        assertThat(venue.path("name").asText("")).isEqualTo("Corn Exchange " + tag);
        assertThat(gigs.path("items")).hasSize(1);
        assertThat(api.get("/api/v1/venues/" + UUID.randomUUID(), fan).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theSourcesNextRunWithTheOldIdLandsOnTheSurvivor() {
        tx.executeWithoutResult(s -> catalogIngest.linkVenue("TICKETMASTER", "tmv-" + tag, from));
        ok(merge(from, into));

        SourceVenue listed =
                new SourceVenue("tmv-" + tag, "Rumble Hall " + tag, null, "Testville", null, lat, lng, "Europe/London");
        SourceGig gig = new SourceGig(
                "TICKETMASTER",
                "tm-vm-" + tag,
                "https://tickets.example.com/vm",
                "Fixture Act " + tag,
                LocalDate.now(UK).plusDays(20).atTime(20, 0).atZone(UK).toInstant(),
                null,
                "SCHEDULED",
                "https://tickets.example.com/vm",
                null,
                null,
                null,
                null,
                listed,
                List.of(new SourceArtist("tma-vm-" + tag, "Fixture Act " + tag, null, List.of(), null)));
        RunStats stats = new RunStats();
        resolver.ingest(gig, stats, Instant.now());

        assertThat(stats.venues.matchedByExternalId).isOne();
        assertThat(dsl.select(GIG.VENUE_ID)
                        .from(GIG)
                        .where(GIG.TITLE.eq("Fixture Act " + tag))
                        .fetchOne(GIG.VENUE_ID))
                .isEqualTo(into);
    }

    @Test
    void aFanNamingTheOldVenueLandsOnTheSurvivorThroughTheAlias() {
        ok(merge(from, into));
        TestApi.User fan = api.register("valias");
        Map<String, Object> body = new HashMap<>();
        body.put("artistNames", List.of("Alias Act " + tag));
        body.put("newVenue", Map.of("name", "Rumble Hall " + tag, "cityName", "Testville", "lat", lat, "lng", lng));
        body.put("date", LocalDate.now(UK).minusDays(2).toString());

        ResponseEntity<JsonNode> added = api.post("/api/v1/gigs", body, fan);

        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(added.getBody().path("item").path("venue").path("id").asText(""))
                .isEqualTo(into.toString());
        // Far away, the same name is another venue: the alias holds only near the survivor.
        body.put("newVenue", Map.of("name", "Rumble Hall " + tag, "cityName", "Faraway", "lat", lat - 3.0, "lng", lng));
        body.put("artistNames", List.of("Alias Act Two " + tag));
        ResponseEntity<JsonNode> far = api.post("/api/v1/gigs", body, fan);
        assertThat(far.getBody().path("item").path("venue").path("id").asText(""))
                .isNotEqualTo(into.toString());
    }

    @Test
    void aChainLeadsToTheLastSurvivorAndRefusalsMoveNothing() {
        UUID last = data.venue("Last Stop " + tag, lat, lng + 0.001);
        ok(merge(from, into));
        ok(merge(into, last));

        assertThat(dsl.select(VENUE_REDIRECT.INTO_VENUE_ID)
                        .from(VENUE_REDIRECT)
                        .where(VENUE_REDIRECT.FROM_VENUE_ID.eq(from))
                        .fetchOne(VENUE_REDIRECT.INTO_VENUE_ID))
                .isEqualTo(last);
        assertThat(dsl.select(VENUE_ALIAS.VENUE_ID)
                        .from(VENUE_ALIAS)
                        .where(VENUE_ALIAS.NAME_NORM.eq(NameNormaliser.normalise("Rumble Hall " + tag)))
                        .fetchOne(VENUE_ALIAS.VENUE_ID))
                .isEqualTo(last);
        assertThat(code(merge(last, last))).isEqualTo("VENUE_MERGE_SAME");
        assertThat(code(merge(from, last))).isEqualTo("VENUE_ALREADY_MERGED");
        assertThat(merge(UUID.randomUUID(), last).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.post("/api/v1/admin/catalog/venues/" + last + "/merge", Map.of("into", into), api.register("vx"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void follow(UUID user, UUID venue, OffsetDateTime at, String notify) {
        dsl.insertInto(VENUE_FOLLOW)
                .set(VENUE_FOLLOW.USER_ID, user)
                .set(VENUE_FOLLOW.VENUE_ID, venue)
                .set(VENUE_FOLLOW.CREATED_AT, at)
                .set(VENUE_FOLLOW.NOTIFY, notify)
                .execute();
    }

    private UUID savedSearch(UUID user, UUID... venues) {
        UUID id = Ids.newId();
        dsl.insertInto(SAVED_GIG_FILTER)
                .set(SAVED_GIG_FILTER.ID, id)
                .set(SAVED_GIG_FILTER.USER_ID, user)
                .set(SAVED_GIG_FILTER.NAME, "Near me")
                .set(SAVED_GIG_FILTER.VENUE_IDS, venues)
                .set(SAVED_GIG_FILTER.PLACE, "UK")
                .execute();
        return id;
    }

    private ResponseEntity<JsonNode> merge(UUID a, UUID b) {
        return api.post("/api/v1/admin/catalog/venues/" + a + "/merge", Map.of("into", b), admin);
    }

    private static Map<String, Long> counts(JsonNode list) {
        Map<String, Long> counts = new HashMap<>();
        list.forEach(m -> counts.put(m.path("what").asText(""), m.path("count").asLong()));
        return counts;
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static String code(ResponseEntity<JsonNode> response) {
        return response.getBody().path("code").asText("");
    }
}
