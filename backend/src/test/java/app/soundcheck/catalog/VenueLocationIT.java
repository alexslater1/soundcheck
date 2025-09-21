package app.soundcheck.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Venues whose map position is probably wrong (fan-added, at a town's centre, or none), and the
 * operator's fix, which moves the venue's gigs too, stays inside the UK and is never overwritten.
 */
class VenueLocationIT extends IntegrationTest {

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
    String tag;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        admin = api.admin();
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void fanAddedAndCityCentreVenuesAreSuspectAndAPlacedOneIsNot() {
        Record3<Integer, Double, Double> city = firstCity();
        TestApi.User fan = api.register("locfan");
        Map<String, Object> body = new HashMap<>();
        body.put("artistNames", List.of("Loc Act " + tag));
        body.put("newVenue", Map.of("name", "Back Room " + tag, "cityId", city.value1()));
        body.put("date", LocalDate.now(UK).minusDays(1).toString());
        ResponseEntity<JsonNode> added = api.post("/api/v1/gigs", body, fan);
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID fanVenue = UUID.fromString(
                added.getBody().path("item").path("venue").path("id").asText(""));
        UUID centre = data.venue("Town Square Hall " + tag, city.value2() + 0.001, city.value3());
        UUID placed = data.venue("Out of Town Barn " + tag, 58.5 + Math.random() / 10, -3.6);

        Map<String, JsonNode> suspects = suspects();

        assertThat(reasons(suspects.get(fanVenue.toString()))).containsExactlyInAnyOrder("FAN_ADDED", "CITY_CENTRE");
        assertThat(reasons(suspects.get(centre.toString()))).containsExactly("CITY_CENTRE");
        assertThat(suspects.get(centre.toString()).path("osmUrl").asText(""))
                .startsWith("https://www.openstreetmap.org/?mlat=");
        assertThat(suspects).doesNotContainKey(placed.toString());
        assertThat(api.get("/api/v1/admin/catalog/venues?suspect=false", admin).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aFixMovesTheVenueAndItsGigsAndNothingOverwritesIt() {
        Record3<Integer, Double, Double> city = firstCity();
        UUID venue = data.venue("Fix Me " + tag, city.value2(), city.value3()); // at a town's centre point
        UUID act = data.artist("Fix Act " + tag);
        UUID gig = data.gig(
                "Fix Act " + tag,
                venue,
                LocalDate.now(UK).plusDays(3).atTime(20, 0).atZone(UK).toInstant(),
                act);
        assertThat(suspects()).containsKey(venue.toString());

        JsonNode fixed =
                ok(api.patch("/api/v1/admin/catalog/venues/" + venue, Map.of("lat", 53.4631, "lng", -2.2913), admin));
        tx.executeWithoutResult(s -> catalogIngest.completeVenue(venue, 51.5, -0.1));

        assertThat(fixed.path("gigsMoved").asInt()).isOne();
        assertThat(fixed.path("osmUrl").asText(""))
                .isEqualTo("https://www.openstreetmap.org/?mlat=53.463100&mlon=-2.291300#map=18/53.463100/-2.291300");
        assertThat(point("venue", venue)).containsExactly(53.4631, -2.2913);
        assertThat(point("gig", gig)).containsExactly(53.4631, -2.2913);
        assertThat(suspects()).doesNotContainKey(venue.toString());
        JsonNode page = ok(api.get("/api/v1/venues/" + venue, api.register("locview")));
        assertThat(page.path("location").path("lat").asDouble()).isEqualTo(53.4631);
    }

    @Test
    void aPointOutsideTheUkIsRefused() {
        UUID venue = data.venue("Abroad " + tag);

        ResponseEntity<JsonNode> paris =
                api.patch("/api/v1/admin/catalog/venues/" + venue, Map.of("lat", 48.8566, "lng", 2.3522), admin);
        ResponseEntity<JsonNode> swapped =
                api.patch("/api/v1/admin/catalog/venues/" + venue, Map.of("lat", -2.2913, "lng", 53.4631), admin);
        ResponseEntity<JsonNode> unknown =
                api.patch("/api/v1/admin/catalog/venues/" + UUID.randomUUID(), Map.of("lat", 53.0, "lng", -2.0), admin);

        assertThat(paris.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(paris.getBody().path("code").asText("")).isEqualTo("LOCATION_OUTSIDE_UK");
        assertThat(swapped.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(point("venue", venue)).containsExactly(53.4808, -2.2426);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Record3<Integer, Double, Double> firstCity() {
        return dsl.resultQuery(
                        "select id, ST_Y(location::geometry) as lat, ST_X(location::geometry) as lng from catalog.city order by id"
                                + " limit 1")
                .coerce(
                        org.jooq.impl.DSL.field("id", Integer.class),
                        org.jooq.impl.DSL.field("lat", Double.class),
                        org.jooq.impl.DSL.field("lng", Double.class))
                .fetchOne();
    }

    /** Every suspect venue by id, following the cursor. */
    private Map<String, JsonNode> suspects() {
        Map<String, JsonNode> byId = new HashMap<>();
        String cursor = "";
        for (int page = 0; page < 200; page++) {
            JsonNode body = ok(api.get("/api/v1/admin/catalog/venues?suspect=true&limit=50&cursor=" + cursor, admin));
            body.path("items").forEach(v -> byId.put(v.path("id").asText(""), v));
            if (body.path("nextCursor").isNull() || body.path("nextCursor").isMissingNode()) {
                return byId;
            }
            cursor = body.path("nextCursor").asText("");
        }
        throw new AssertionError("too many pages");
    }

    private static List<String> reasons(JsonNode venue) {
        assertThat(venue).as("venue in the suspect list").isNotNull();
        List<String> reasons = new ArrayList<>();
        venue.path("reasons").forEach(r -> reasons.add(r.asText("")));
        return reasons;
    }

    private List<Double> point(String table, UUID id) {
        var r = dsl.fetchOne(
                "select ST_Y(location::geometry), ST_X(location::geometry) from catalog." + table + " where id = ?",
                id);
        return List.of(r.get(0, Double.class), r.get(1, Double.class));
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
