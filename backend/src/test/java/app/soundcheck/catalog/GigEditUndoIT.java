package app.soundcheck.catalog;

import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_EDIT;
import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.shared.Ids;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
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
 * The operator sees a gig's edits and undoes one. The undo puts the old value back and is logged
 * as an edit of the same field that points at it; a later edit of the same thing refuses the undo; an act
 * with clips stays on the bill; undoing an undo restores the edit.
 */
class GigEditUndoIT extends IntegrationTest {

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
    TestApi.User admin;
    TestApi.User fan;
    String tag;
    UUID venue;
    UUID gig;
    UUID headliner;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        admin = api.admin();
        fan = api.register("edfan");
        tag = UUID.randomUUID().toString().substring(0, 8);
        venue = data.venue("Edit Rooms " + tag);
        headliner = data.artist("Edit Head " + tag);
        // A gig the fan added, so they may change its date and venue too.
        Map<String, Object> body = new HashMap<>();
        body.put("artistIds", List.of(headliner));
        body.put("venueId", venue);
        body.put("date", LocalDate.now(UK).minusDays(5).toString());
        ResponseEntity<JsonNode> added = api.post("/api/v1/gigs", body, fan);
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        gig = UUID.fromString(added.getBody().path("item").path("id").asText(""));
    }

    @Test
    void undoingTheStartTimePutsItBackAndLogsTheUndo() {
        Instant before = startsAt();
        edit(Map.of("startTime", "21:30"));
        JsonNode original = latest("STARTS_AT");

        JsonNode undo = ok(undo(original));
        JsonNode history = ok(api.get("/api/v1/admin/catalog/gigs/" + gig + "/edits", admin));

        assertThat(startsAt()).isEqualTo(before);
        assertThat(undo.path("field").asText("")).isEqualTo("STARTS_AT");
        assertThat(undo.path("undoesEditId").asText(""))
                .isEqualTo(original.path("id").asText(""));
        assertThat(undo.path("userId").asText("")).isEqualTo(admin.id().toString());
        assertThat(history.get(0).path("id").asText(""))
                .isEqualTo(undo.path("id").asText(""));
        assertThat(history.get(1).path("undoneByEditId").asText(""))
                .isEqualTo(undo.path("id").asText(""));
        assertThat(history.get(1).path("userId").asText("")).isEqualTo(fan.id().toString());
    }

    @Test
    void everyGigsEditsAreListedNewestFirstAPageAtATime() {
        edit(Map.of("startTime", "22:15"));
        JsonNode original = latest("STARTS_AT");
        JsonNode undo = ok(undo(original));

        JsonNode first = ok(api.get("/api/v1/admin/catalog/gig-edits?limit=1", admin));
        assertThat(first.path("items").size()).isEqualTo(1);
        JsonNode newest = first.path("items").get(0);
        assertThat(newest.path("edit").path("id").asText(""))
                .isEqualTo(undo.path("id").asText(""));
        assertThat(newest.path("gigTitle").asText("")).isNotBlank();
        assertThat(newest.path("gigStartsAt").asText("")).isNotBlank();

        String cursor = first.path("nextCursor").asText("");
        assertThat(cursor).isNotBlank();
        JsonNode second = ok(api.get("/api/v1/admin/catalog/gig-edits?limit=1&cursor=" + cursor, admin));
        JsonNode older = second.path("items").get(0).path("edit");
        assertThat(older.path("id").asText("")).isEqualTo(original.path("id").asText(""));
        // Its undo is on the page before, and still named.
        assertThat(older.path("undoneByEditId").asText(""))
                .isEqualTo(undo.path("id").asText(""));
    }

    @Test
    void undoingTheDateAndTheVenuePutsThemBackWithTheGigsPoint() {
        Instant before = startsAt();
        UUID elsewhere = data.venue("Edit Elsewhere " + tag, 51.45, -2.58);
        edit(Map.of("date", LocalDate.now(UK).minusDays(6).toString(), "venueId", elsewhere));
        assertThat(venueOfGig()).isEqualTo(elsewhere);

        ok(undo(latest("DATE")));
        JsonNode venueUndo = ok(undo(latest("VENUE")));

        assertThat(startsAt()).isEqualTo(before);
        assertThat(venueOfGig()).isEqualTo(venue);
        assertThat(dsl.fetchValue("select ST_Y(location::geometry) from catalog.gig where id = ?", gig))
                .isEqualTo(53.4808);
        assertThat(venueUndo.path("newLabel").asText("")).isEqualTo("Edit Rooms " + tag);
    }

    @Test
    void undoingTheHeadlinerAndTheTitlePutsBothBack() {
        UUID support = data.artist("Edit Support " + tag);
        edit(Map.of("addArtistIds", List.of(support)));
        String title = title();
        edit(Map.of("headlinerId", support));
        assertThat(bill()).first().isEqualTo(support);

        ok(undo(latest("HEADLINER")));
        boolean renamed = !title.equals(title());
        if (renamed) {
            ok(undo(latest("TITLE")));
        }

        assertThat(bill()).containsExactly(headliner, support);
        assertThat(title()).isEqualTo(title);
    }

    @Test
    void anAddedActComesOffTheBillAndUndoingTheUndoPutsItBack() {
        UUID support = data.artist("Edit Act " + tag);
        edit(Map.of("addArtistIds", List.of(support)));
        JsonNode added = latest("ACT_ADDED");

        JsonNode removed = ok(undo(added));
        assertThat(bill()).containsExactly(headliner);
        assertThat(removed.path("oldValue").asText("")).isEqualTo(support.toString());
        assertThat(removed.path("newValue").isNull()).isTrue();
        assertThat(code(undo(added))).isEqualTo("EDIT_SUPERSEDED");

        JsonNode restored = ok(undo(removed));

        assertThat(bill()).containsExactly(headliner, support);
        assertThat(restored.path("newValue").asText("")).isEqualTo(support.toString());
        assertThat(restored.path("undoesEditId").asText(""))
                .isEqualTo(removed.path("id").asText(""));
    }

    @Test
    void aLaterEditOfTheSameThingRefusesTheUndo() {
        edit(Map.of("startTime", "21:00"));
        JsonNode first = latest("STARTS_AT");
        edit(Map.of("startTime", "22:00"));
        JsonNode second = latest("STARTS_AT");
        // Another act added later doesn't touch the start time: the first is still superseded only by the second.
        edit(Map.of("addArtistIds", List.of(data.artist("Unrelated " + tag))));

        ResponseEntity<JsonNode> refused = undo(first);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(refused)).isEqualTo("EDIT_SUPERSEDED");
        assertThat(refused.getBody().path("detail").asText(""))
                .contains(second.path("id").asText(""));
        assertThat(undo(second).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anActWithClipsStaysAndAMergeCantBeUndone() {
        UUID support = data.artist("Clip Act " + tag);
        edit(Map.of("addArtistIds", List.of(support)));
        TestApi.User poster = api.register("edclip");
        assertThat(api.post(
                                "/api/v1/posts",
                                Map.of(
                                        "clips",
                                        List.of(Map.of(
                                                "mediaId", data.video(poster.id(), "READY"), "artistId", support)),
                                        "gigId",
                                        gig),
                                poster)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        UUID merge = Ids.newId();
        dsl.insertInto(GIG_EDIT)
                .set(GIG_EDIT.ID, merge)
                .set(GIG_EDIT.GIG_ID, gig)
                .set(GIG_EDIT.USER_ID, admin.id())
                .set(GIG_EDIT.FIELD, "MERGE")
                .set(GIG_EDIT.OLD_VALUE, UUID.randomUUID().toString())
                .set(GIG_EDIT.NEW_VALUE, gig.toString())
                .execute();

        assertThat(code(undo(latest("ACT_ADDED")))).isEqualTo("ACT_HAS_CLIPS");
        assertThat(bill()).contains(support);
        ResponseEntity<JsonNode> mergeUndo =
                api.post("/api/v1/admin/catalog/gig-edits/" + merge + "/undo", Map.of(), admin);
        assertThat(code(mergeUndo)).isEqualTo("MERGE_NOT_UNDOABLE");
        assertThat(latest("MERGE").path("undoable").asBoolean()).isFalse();
        assertThat(api.post("/api/v1/admin/catalog/gig-edits/" + UUID.randomUUID() + "/undo", Map.of(), admin)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void edit(Map<String, Object> body) {
        ResponseEntity<JsonNode> response = api.patch("/api/v1/gigs/" + gig, body, fan);
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
    }

    /** The newest edit of the field, as the console lists it. */
    private JsonNode latest(String field) {
        for (JsonNode edit : ok(api.get("/api/v1/admin/catalog/gigs/" + gig + "/edits", admin))) {
            if (edit.path("field").asText("").equals(field)) {
                return edit;
            }
        }
        throw new AssertionError("no " + field + " edit");
    }

    private ResponseEntity<JsonNode> undo(JsonNode edit) {
        return api.post("/api/v1/admin/catalog/gig-edits/" + edit.path("id").asText("") + "/undo", Map.of(), admin);
    }

    private Instant startsAt() {
        return dsl.select(GIG.STARTS_AT)
                .from(GIG)
                .where(GIG.ID.eq(gig))
                .fetchOne(GIG.STARTS_AT)
                .toInstant();
    }

    private UUID venueOfGig() {
        return dsl.select(GIG.VENUE_ID).from(GIG).where(GIG.ID.eq(gig)).fetchOne(GIG.VENUE_ID);
    }

    private String title() {
        return dsl.select(GIG.TITLE).from(GIG).where(GIG.ID.eq(gig)).fetchOne(GIG.TITLE);
    }

    private List<UUID> bill() {
        return new ArrayList<>(dsl.select(GIG_ARTIST.ARTIST_ID)
                .from(GIG_ARTIST)
                .where(GIG_ARTIST.GIG_ID.eq(gig))
                .orderBy(GIG_ARTIST.BILLING_ORDER)
                .fetch(GIG_ARTIST.ARTIST_ID));
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
