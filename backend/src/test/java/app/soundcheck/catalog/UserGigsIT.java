package app.soundcheck.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Over HTTP against real PostGIS: gigs and acts added by users and the searches
 * built on them — filter by artist or venue, nationwide, the calendar's day counts, venue search and the
 * viewer's own gigs in a window. Every name carries a per-test tag, so nothing collides in the shared
 * database.
 */
class UserGigsIT extends IntegrationTest {

    static final ZoneId UK = ZoneId.of("Europe/London");

    @Autowired
    TestRestTemplate rest;

    TestApi api;
    TestApi.User fan;
    String tag;
    int manchester;
    int glasgow;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        fan = api.register("fan");
        tag = UUID.randomUUID().toString().substring(0, 8);
        manchester = cityId("Manchester");
        glasgow = cityId("Glasgow");
    }

    @Test
    void aFanAddsAPubGigWithNewActsAndANewVenueAndASecondFanLandsOnTheSameGig() {
        LocalDate lastFriday = LocalDate.now(UK).minusDays(5);
        ResponseEntity<JsonNode> first = api.post(
                "/api/v1/gigs",
                gig(
                        List.of("Pale Kites " + tag, "Night Bus " + tag),
                        newVenue("The Snug " + tag, manchester),
                        lastFriday),
                fan);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode added = first.getBody().path("item");
        assertThat(first.getBody().path("created").asBoolean()).isTrue();
        assertThat(added.path("title").asText("")).isEqualTo("Pale Kites " + tag);
        assertThat(added.path("headliner").path("name").asText("")).isEqualTo("Pale Kites " + tag);
        assertThat(added.path("support").path(0).path("name").asText("")).isEqualTo("Night Bus " + tag);
        assertThat(added.path("venue").path("name").asText("")).isEqualTo("The Snug " + tag);
        assertThat(added.path("venue").path("city").asText("")).isEqualTo("Manchester");
        assertThat(added.path("userAdded").asBoolean()).isTrue();
        // 20:00 UK time when no time is given; the person who added a past gig went to it.
        assertThat(added.path("startsAt").asText(""))
                .isEqualTo(lastFriday.atTime(20, 0).atZone(UK).toInstant().toString());
        assertThat(added.path("myAttendance").asText("")).isEqualTo("WENT");

        // Another fan adds the same show: a slightly different venue name, the act in other capitals, a time.
        TestApi.User other = api.register("other");
        Map<String, Object> again = gig(List.of("PALE KITES " + tag), newVenue("Snug " + tag, manchester), lastFriday);
        again.put("time", "21:30");
        ResponseEntity<JsonNode> second = api.post("/api/v1/gigs", again, other);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().path("created").asBoolean()).isFalse();
        assertThat(second.getBody().path("item").path("id").asText(""))
                .isEqualTo(added.path("id").asText(""));
        assertThat(second.getBody().path("item").path("myAttendance").asText(""))
                .isEqualTo("WENT");

        // A future gig marks the adder GOING.
        ResponseEntity<JsonNode> future = api.post(
                "/api/v1/gigs",
                gigAt(
                        List.of("Pale Kites " + tag),
                        added.path("venue").path("id").asText(""),
                        lastFriday.plusDays(30)),
                fan);
        assertThat(future.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(future.getBody().path("item").path("myAttendance").asText(""))
                .isEqualTo("GOING");
    }

    @Test
    void aGigPickedFromSetlistFmKeepsOnlyTheSetlistsIdAndLinkAndIsFoundAgainByIt() {
        LocalDate day = LocalDate.now(UK).minusMonths(14);
        String setlistId = "sl" + tag;
        String url = "https://www.setlist.fm/setlist/x/2025/y-" + setlistId + ".html";
        Map<String, Object> body = new HashMap<>();
        body.put("artistNames", List.of("Setlist Act " + tag));
        body.put("newVenue", Map.of("name", "Old Hall " + tag, "cityName", "Leeds", "lat", 53.7997, "lng", -1.5492));
        body.put("date", day.toString());
        body.put(
                "foundOn",
                Map.of(
                        "source",
                        "SETLISTFM",
                        "externalId",
                        setlistId,
                        "url",
                        url,
                        "artistMbid",
                        "5b11f4ce-a62d-471e-81fc-a69a8278c7da"));

        ResponseEntity<JsonNode> first = api.post("/api/v1/gigs", body, fan);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode gig = first.getBody().path("item");
        assertThat(gig.path("venue").path("city").asText("")).isEqualTo("Leeds");
        assertThat(gig.path("venue").path("location").path("lat").asDouble()).isEqualTo(53.7997);

        // The gig page links back to setlist.fm (its terms), next to the fan's own listing.
        JsonNode detail = ok(api.get("/api/v1/gigs/" + gig.path("id").asText(""), fan));
        List<String> sources = new ArrayList<>();
        detail.path("sources")
                .forEach(src -> sources.add(
                        src.path("source").asText("") + " " + src.path("url").asText("")));
        assertThat(sources).contains("SETLISTFM " + url);

        // Another fan picks the same setlist (even with another spelling of the venue): the same gig.
        TestApi.User other = api.register("other");
        body.put(
                "newVenue",
                Map.of("name", "The Old Hall Leeds " + tag, "cityName", "Leeds", "lat", 53.8, "lng", -1.55));
        ResponseEntity<JsonNode> second = api.post("/api/v1/gigs", body, other);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().path("item").path("id").asText(""))
                .isEqualTo(gig.path("id").asText(""));
        assertThat(second.getBody().path("item").path("myAttendance").asText(""))
                .isEqualTo("WENT");

        // Only setlist.fm, only links into www.setlist.fm.
        Map<String, Object> bad = new HashMap<>(body);
        bad.put("foundOn", Map.of("source", "DICE", "externalId", "x"));
        assertThat(code(api.post("/api/v1/gigs", bad, fan))).isEqualTo("FOUND_ON_INVALID");
        bad.put("foundOn", Map.of("source", "SETLISTFM", "externalId", "y" + tag, "url", "https://evil.example/x"));
        assertThat(code(api.post("/api/v1/gigs", bad, fan))).isEqualTo("FOUND_ON_INVALID");
        bad.put("foundOn", null);
        bad.put("newVenue", Map.of("name", "Nowhere " + tag));
        assertThat(code(api.post("/api/v1/gigs", bad, fan))).isEqualTo("VENUE_PLACE_REQUIRED");
    }

    @Test
    void addingAnActReturnsTheKnownOneOrCreatesIt() {
        ResponseEntity<JsonNode> created = api.post("/api/v1/artists", Map.of("name", "  Lantern   Club " + tag), fan);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().path("item").path("name").asText("")).isEqualTo("Lantern Club " + tag);

        ResponseEntity<JsonNode> known = api.post("/api/v1/artists", Map.of("name", "the lantern club " + tag), fan);
        assertThat(known.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(known.getBody().path("item").path("id").asText(""))
                .isEqualTo(created.getBody().path("item").path("id").asText(""));

        assertThat(api.post("/api/v1/artists", Map.of("name", " "), fan).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.post("/api/v1/artists", Map.of("name", "x"), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void invalidGigsAreRefusedWithAStableCode() {
        LocalDate day = LocalDate.now(UK);
        assertThat(code(api.post("/api/v1/gigs", gig(List.of(), newVenue("V " + tag, manchester), day), fan)))
                .isEqualTo("GIG_NEEDS_AN_ACT");
        Map<String, Object> both = gig(List.of("A " + tag), newVenue("V " + tag, manchester), day);
        both.put("venueId", UUID.randomUUID().toString());
        assertThat(code(api.post("/api/v1/gigs", both, fan))).isEqualTo("VENUE_REQUIRED");
        Map<String, Object> neither = gig(List.of("A " + tag), null, day);
        assertThat(code(api.post("/api/v1/gigs", neither, fan))).isEqualTo("VENUE_REQUIRED");
        assertThat(code(api.post("/api/v1/gigs", gig(List.of("A " + tag), newVenue("V", 999_999), day), fan)))
                .isEqualTo("CITY_UNKNOWN");
        assertThat(code(api.post(
                        "/api/v1/gigs",
                        gig(List.of("A " + tag), newVenue("V " + tag, manchester), day.minusYears(11)),
                        fan)))
                .isEqualTo("GIG_DATE_OUT_OF_RANGE");
        Map<String, Object> unknownAct = gig(List.of(), newVenue("V " + tag, manchester), day);
        unknownAct.put("artistIds", List.of(UUID.randomUUID().toString()));
        assertThat(code(api.post("/api/v1/gigs", unknownAct, fan))).isEqualTo("ARTIST_UNKNOWN");
    }

    @Test
    void gigsCanBeFilteredByArtistAndVenueNationwideAndBackInTime() {
        LocalDate day = LocalDate.now(UK).minusDays(3);
        JsonNode inGlasgow = item(
                api.post("/api/v1/gigs", gig(List.of("Far Act " + tag), newVenue("Hall " + tag, glasgow), day), fan));
        String artistId = inGlasgow.path("headliner").path("id").asText("");
        String from = day.minusDays(1).atStartOfDay(UK).toInstant().toString();

        // The fan has no home city: no radius applies anyway. With one in Manchester, Glasgow is out of
        // the default 25 km unless the search is nationwide.
        assertThat(api.patch("/api/v1/me", Map.of("homeCityId", manchester), fan)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        String byArtist = "/api/v1/gigs?artistId=" + artistId + "&from=" + from;
        assertThat(ok(api.get(byArtist, fan)).path("items")).isEmpty();
        JsonNode nationwide = ok(api.get(byArtist + "&nationwide=true", fan));
        assertThat(nationwide.path("items")).hasSize(1);
        assertThat(nationwide.path("items").path(0).path("distanceKm").asDouble())
                .isGreaterThan(250);

        String byVenue =
                "/api/v1/gigs?venueId=" + inGlasgow.path("venue").path("id").asText("") + "&nationwide=true";
        assertThat(ok(api.get(byVenue, fan)).path("items")).isEmpty(); // default window starts now
        assertThat(ok(api.get(byVenue + "&from=" + from, fan)).path("items")).hasSize(1);

        // The clip picker's search: newest first, one page at a time.
        String venueId = inGlasgow.path("venue").path("id").asText("");
        api.post("/api/v1/gigs", gigAt(List.of("Earlier Act " + tag), venueId, day.minusDays(2)), fan);
        String newest = "/api/v1/gigs?venueId=" + venueId + "&nationwide=true&order=desc&limit=1&from="
                + day.minusDays(10).atStartOfDay(UK).toInstant() + "&to="
                + day.plusDays(1).atStartOfDay(UK).toInstant();
        JsonNode first = ok(api.get(newest, fan));
        assertThat(first.path("items").path(0).path("title").asText("")).isEqualTo("Far Act " + tag);
        JsonNode second =
                ok(api.get(newest + "&cursor=" + first.path("nextCursor").asText(""), fan));
        assertThat(second.path("items").path(0).path("title").asText("")).isEqualTo("Earlier Act " + tag);
        assertThat(code(api.get(newest + "&order=sideways", fan))).isEqualTo("ORDER_INVALID");
    }

    @Test
    void theCalendarCountsMatchingGigsPerUkDay() {
        // Mid-month days so the window's month is unambiguous; times chosen around UK midnight.
        LocalDate base = LocalDate.now(UK).withDayOfMonth(10);
        String venueId = item(api.post(
                        "/api/v1/gigs",
                        gig(List.of("Day One " + tag), newVenue("Cellar " + tag, manchester), base),
                        fan))
                .path("venue")
                .path("id")
                .asText("");
        Map<String, Object> late = gigAt(List.of("Late Show " + tag), venueId, base);
        late.put("time", "23:30");
        api.post("/api/v1/gigs", late, fan);
        Map<String, Object> early = gigAt(List.of("After Hours " + tag), venueId, base.plusDays(1));
        early.put("time", "00:30");
        api.post("/api/v1/gigs", early, fan);
        api.post("/api/v1/gigs", gigAt(List.of("Next Week " + tag), venueId, base.plusDays(7)), fan);

        String window = "from=" + base.withDayOfMonth(1).atStartOfDay(UK).toInstant() + "&to="
                + base.withDayOfMonth(1).plusMonths(1).atStartOfDay(UK).toInstant();
        JsonNode days = ok(api.get("/api/v1/gigs/days?venueId=" + venueId + "&nationwide=true&" + window, fan));

        Map<String, Integer> counts = new HashMap<>();
        days.forEach(d -> counts.put(d.path("date").asText(""), d.path("count").asInt()));
        assertThat(counts)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        base.toString(), 2,
                        base.plusDays(1).toString(), 1,
                        base.plusDays(7).toString(), 1));

        // The search box filters the counts like the list.
        JsonNode searched = ok(api.get(
                "/api/v1/gigs/days?venueId=" + venueId + "&nationwide=true&q=late+show+" + tag + "&" + window, fan));
        assertThat(searched).hasSize(1);
        assertThat(searched.path(0).path("date").asText("")).isEqualTo(base.toString());

        // A missing required parameter is the API's generic BAD_REQUEST (ProblemHandler).
        assertThat(code(api.get("/api/v1/gigs/days?" + window.replace("&to=", "&x="), fan)))
                .isEqualTo("BAD_REQUEST");
        String tooLong = "from=" + base.atStartOfDay(UK).toInstant() + "&to="
                + base.plusDays(150).atStartOfDay(UK).toInstant();
        assertThat(code(api.get("/api/v1/gigs/days?" + tooLong, fan))).isEqualTo("WINDOW_INVALID");
    }

    @Test
    void venuesAreFoundByNameAndTheViewersGigsByWindow() {
        LocalDate day = LocalDate.now(UK).minusDays(40);
        JsonNode gig = item(api.post(
                "/api/v1/gigs",
                gig(List.of("Venue Seeker " + tag), newVenue("Brickworks " + tag, manchester), day),
                fan));

        JsonNode found = ok(api.get("/api/v1/venues?q=brickworks+" + tag, fan));
        assertThat(found.path(0).path("name").asText("")).isEqualTo("Brickworks " + tag);
        assertThat(found.path(0).path("city").asText("")).isEqualTo("Manchester");

        String window = "from=" + day.minusDays(1).atStartOfDay(UK).toInstant() + "&to="
                + day.plusDays(1).atStartOfDay(UK).toInstant();
        JsonNode mine = ok(api.get("/api/v1/me/gigs?" + window, fan));
        List<String> ids = new ArrayList<>();
        mine.path("items").forEach(g -> ids.add(g.path("id").asText("")));
        assertThat(ids).containsExactly(gig.path("id").asText(""));
        // Without a window, "upcoming" by default: a gig 40 days ago is not in it.
        assertThat(ok(api.get("/api/v1/me/gigs", fan)).path("items").toString())
                .doesNotContain(gig.path("id").asText(""));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Map<String, Object> gig(List<String> names, Map<String, Object> venue, LocalDate date) {
        Map<String, Object> body = new HashMap<>();
        body.put("artistNames", names);
        if (venue != null) {
            body.put("newVenue", venue);
        }
        body.put("date", date.toString());
        return body;
    }

    private Map<String, Object> gigAt(List<String> names, String venueId, LocalDate date) {
        Map<String, Object> body = new HashMap<>();
        body.put("artistNames", names);
        body.put("venueId", venueId);
        body.put("date", date.toString());
        return body;
    }

    private static Map<String, Object> newVenue(String name, int cityId) {
        return Map.of("name", name, "cityId", cityId);
    }

    private int cityId(String name) {
        JsonNode cities = rest.getForObject("/api/v1/cities?q=" + name, JsonNode.class);
        for (JsonNode city : cities) {
            if (city.path("name").asText("").equals(name)) {
                return city.path("id").asInt();
            }
        }
        throw new IllegalStateException("no city " + name);
    }

    private static JsonNode item(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as(String.valueOf(response.getBody()))
                .isTrue();
        return response.getBody().path("item");
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static String code(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().is4xxClientError())
                .as(String.valueOf(response.getBody()))
                .isTrue();
        return response.getBody().path("code").asText("");
    }
}
