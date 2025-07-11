package app.soundcheck.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.soundcheck.ingestion.internal.RunStats;
import app.soundcheck.ingestion.internal.resolve.EntityResolver;
import app.soundcheck.ingestion.internal.source.SourceArtist;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceVenue;
import app.soundcheck.social.internal.ArtistFollowService;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The catalog API over HTTP against real PostGIS: radius correctness, keyset paging, filters,
 * attendance idempotency, artists, follows, venues and search.
 *
 * <p>Each test creates its own gigs around a point where no other test puts any (north of Shetland, a
 * different spot per test), so radius queries see exactly this test's data in the shared database.
 */
class CatalogApiIT extends IntegrationTest {

    /** 1 km north is this many degrees of latitude. */
    static final double KM_LAT = 1 / 111.2;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    EntityResolver resolver;

    @Autowired
    ArtistFollowService follows;

    @Autowired
    DSLContext dsl;

    TestApi api;
    TestApi.User fan;
    String tag;
    Instant base;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        fan = api.register("fan");
        tag = UUID.randomUUID().toString().substring(0, 8);
        base = Instant.now().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.HOURS);
    }

    @Test
    void radiusFilterIncludesTwentyKilometresAndExcludesThirty() {
        double lat = 60.90, lng = -0.90;
        gig("near", base, venue("five-km", lat + 5 * KM_LAT, lng), "Near Act");
        gig("mid", base.plusSeconds(3600), venue("twenty-km", lat + 20 * KM_LAT, lng), "Mid Act");
        gig("far", base.plusSeconds(7200), venue("thirty-km", lat + 30 * KM_LAT, lng), "Far Act");

        JsonNode defaultRadius = ok(api.get("/api/v1/gigs?lat=" + lat + "&lng=" + lng, fan));
        assertThat(titles(defaultRadius)).containsExactly("near " + tag, "mid " + tag); // 25 km by default
        assertThat(defaultRadius.path("items").path(0).path("distanceKm").asDouble())
                .isCloseTo(5.0, within(0.2));
        assertThat(defaultRadius.path("items").path(1).path("distanceKm").asDouble())
                .isCloseTo(20.0, within(0.2));
        assertThat(defaultRadius.path("nextCursor").isNull()).isTrue();

        JsonNode wider = ok(api.get("/api/v1/gigs?lat=" + lat + "&lng=" + lng + "&radiusKm=35", fan));
        assertThat(titles(wider)).containsExactly("near " + tag, "mid " + tag, "far " + tag);
        assertThat(wider.path("items").path(2).path("distanceKm").asDouble()).isCloseTo(30.0, within(0.2));

        JsonNode tight = ok(api.get("/api/v1/gigs?lat=" + lat + "&lng=" + lng + "&radiusKm=10", fan));
        assertThat(titles(tight)).containsExactly("near " + tag);

        ResponseEntity<JsonNode> tooWide = api.get("/api/v1/gigs?lat=" + lat + "&lng=" + lng + "&radiusKm=500", fan);
        assertThat(tooWide.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); // max 200
        assertThat(tooWide.getBody().path("code").asText("")).isEqualTo("VALIDATION_FAILED");
        assertThat(tooWide.getBody().path("errors").path(0).path("field").asText(""))
                .isEqualTo("radiusKm");
        assertThat(api.get("/api/v1/gigs?lat=" + lat, fan)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("LOCATION_INCOMPLETE");
        assertThat(api.get("/api/v1/gigs", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void keysetPagingWalksEveryGigOnceInStartOrderEvenWithEqualStartTimes() {
        double lat = 61.40, lng = -0.80;
        // Seven gigs; three share one start time, so the id has to break the tie.
        List<Instant> starts = List.of(
                base,
                base.plusSeconds(600),
                base.plusSeconds(600),
                base.plusSeconds(600),
                base.plusSeconds(1200),
                base.plusSeconds(1800),
                base.plusSeconds(2400));
        for (int i = 0; i < starts.size(); i++) {
            // One venue per gig: a venue cannot host two shows at the same time (they would be merged).
            gig("page" + i, starts.get(i), venue("paging" + i, lat, lng), "Paging Act " + i);
        }

        List<String> seenIds = new ArrayList<>();
        List<Instant> seenStarts = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String url = "/api/v1/gigs?lat=" + lat + "&lng=" + lng + "&limit=3"
                    + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = ok(api.get(url, fan));
            assertThat(page.path("items").size()).isLessThanOrEqualTo(3);
            page.path("items").forEach(item -> {
                seenIds.add(item.path("id").asText(""));
                seenStarts.add(Instant.parse(item.path("startsAt").asText("")));
            });
            cursor = page.path("nextCursor").isNull()
                    ? null
                    : page.path("nextCursor").asText("");
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(pages).isEqualTo(3); // 3 + 3 + 1
        assertThat(seenIds).hasSize(7).doesNotHaveDuplicates();
        assertThat(seenStarts).isSorted();
        // Within the tie, ids ascend (UUIDv7 ids compare like strings).
        assertThat(seenIds.subList(1, 4)).isSorted();

        ResponseEntity<JsonNode> bad = api.get("/api/v1/gigs?lat=" + lat + "&lng=" + lng + "&cursor=not-a-cursor", fan);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody().path("code").asText("")).isEqualTo("CURSOR_INVALID");
    }

    @Test
    void windowGenreAndTextFilters() {
        double lat = 61.90, lng = -0.70;
        SourceVenue venue = venue("filters", lat, lng);
        gig("jazz", base, venue, new SourceArtist("a-jazz-" + tag, "Sigrún Ósk " + tag, null, List.of("Jazz"), null));
        gig(
                "indie",
                base.plus(Duration.ofDays(2)),
                venue,
                new SourceArtist("a-indie-" + tag, "Indie Kid " + tag, null, List.of("Indie"), null));
        gig(
                "past",
                Instant.now().minus(Duration.ofDays(2)),
                venue,
                new SourceArtist("a-past-" + tag, "Old Act " + tag, null, List.of("Jazz"), null));
        String here = "/api/v1/gigs?lat=" + lat + "&lng=" + lng;

        // Default window: from now on.
        assertThat(titles(ok(api.get(here, fan)))).containsExactly("jazz " + tag, "indie " + tag);
        // Explicit window.
        assertThat(titles(ok(api.get(here + "&from=" + base.plus(Duration.ofDays(1)), fan))))
                .containsExactly("indie " + tag);
        assertThat(titles(ok(api.get(here + "&to=" + base.plus(Duration.ofDays(1)), fan))))
                .containsExactly("jazz " + tag);
        assertThat(titles(ok(api.get(here + "&from=" + Instant.now().minus(Duration.ofDays(5)), fan))))
                .containsExactly("past " + tag, "jazz " + tag, "indie " + tag);

        // Genre by slug; a parent genre includes its children (indie is a child of rock).
        assertThat(titles(ok(api.get(here + "&genre=jazz", fan)))).containsExactly("jazz " + tag);
        assertThat(titles(ok(api.get(here + "&genre=rock", fan)))).containsExactly("indie " + tag);
        assertThat(api.get(here + "&genre=polka", fan).getBody().path("code").asText(""))
                .isEqualTo("GENRE_UNKNOWN");

        // Text: gig title, or an act on the bill (accents and case ignored).
        assertThat(titles(ok(api.get(here + "&q=sigrun osk", fan)))).containsExactly("jazz " + tag);
        assertThat(titles(ok(api.get(here + "&q=INDIE " + tag, fan)))).containsExactly("indie " + tag);
        assertThat(titles(ok(api.get(here + "&q=nothing-matches-this", fan)))).isEmpty();
    }

    @Test
    void withoutCoordinatesTheViewersHomeCityIsTheOrigin() {
        // No home city yet: the list is UK-wide and carries no distances.
        gig("anywhere", base, venue("anywhere", 59.90, -1.30), "Anywhere Act");
        JsonNode anywhere = ok(api.get("/api/v1/gigs?q=anywhere " + tag, fan));
        assertThat(titles(anywhere)).containsExactly("anywhere " + tag);
        assertThat(anywhere.path("items").path(0).has("distanceKm")).isTrue();
        assertThat(anywhere.path("items").path(0).path("distanceKm").isNull()).isTrue();

        // Dates PostgreSQL cannot store are a 400, not a 500; a cursor carrying one is just an invalid cursor.
        assertThat(api.get("/api/v1/gigs?from=2500-01-01T00:00:00Z", fan)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("DATE_OUT_OF_RANGE");
        assertThat(api.get("/api/v1/gigs?from=%2B300000-01-01T00:00:00Z", fan).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        String farFuture = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("[\"+300000-01-01T00:00:00Z\",\"" + UUID.randomUUID() + "\"]").getBytes());
        assertThat(api.get("/api/v1/gigs?cursor=" + farFuture, fan)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("CURSOR_INVALID");

        // Manchester as home city → gigs within 25 km of its centroid, with distances.
        JsonNode cities = ok(api.get("/api/v1/cities?q=manchester", null));
        int manchester = cities.path(0).path("id").asInt();
        double lat = cities.path(0).path("lat").asDouble(),
                lng = cities.path(0).path("lng").asDouble();
        assertThat(api.patch("/api/v1/me", Map.of("homeCityId", manchester), fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        gig("home", base, venue("home-near", lat + 2 * KM_LAT, lng), "Home Act");
        gig("away", base, venue("home-far", lat + 60 * KM_LAT, lng), "Away Act");

        JsonNode nearHome = ok(api.get("/api/v1/gigs?q=" + tag, fan));
        assertThat(titles(nearHome)).containsExactly("home " + tag);
        assertThat(nearHome.path("items").path(0).path("distanceKm").asDouble()).isCloseTo(2.0, within(0.2));
        assertThat(titles(ok(api.get("/api/v1/gigs?radiusKm=100&q=" + tag, fan))))
                .containsExactlyInAnyOrder("home " + tag, "away " + tag);
    }

    /**
     * With the viewer's position, every list measures distances from it (rounded to 0.01°,
     * about 1 km), while the search area stays where it was.
     */
    @Test
    void distancesAreFromTheViewersRoundedPositionWhileTheSearchAreaStays() {
        double lat = 63.90, lng = -0.30;
        UUID gigId = gig("here", base, venue("here", lat, lng), "Here Act");
        JsonNode summary = ok(api.get("/api/v1/gigs/" + gigId, fan)).path("gig");
        String venueId = summary.path("venue").path("id").asText("");
        String artistId = summary.path("headliner").path("id").asText("");
        // 0.09° north of the venue is 10.0 km; sent with more decimals, it would be 10.6 km.
        String from = "fromLat=63.99449&fromLng=" + lng;
        String area = "/api/v1/gigs?lat=" + lat + "&lng=" + lng;

        JsonNode atVenue = ok(api.get(area, fan));
        assertThat(atVenue.path("items").path(0).path("distanceKm").asDouble()).isCloseTo(0.0, within(0.05));

        JsonNode fromHere = ok(api.get(area + "&radiusKm=5&" + from, fan));
        assertThat(titles(fromHere)).containsExactly("here " + tag); // the area is still 5 km around the venue
        assertThat(distance(fromHere.path("items").path(0))).isCloseTo(10.0, within(0.05));

        assertThat(distance(
                        ok(api.get("/api/v1/gigs/" + gigId + "?" + from, fan)).path("gig")))
                .isCloseTo(10.0, within(0.05));
        assertThat(distance(ok(api.get("/api/v1/artists/" + artistId + "?" + from, fan))
                        .path("upcomingGigs")
                        .path(0)))
                .isCloseTo(10.0, within(0.05));
        assertThat(distance(ok(api.get("/api/v1/artists/" + artistId + "/gigs?" + from, fan))
                        .path("items")
                        .path(0)))
                .isCloseTo(10.0, within(0.05));
        assertThat(distance(ok(api.get("/api/v1/venues/" + venueId + "/gigs?" + from, fan))
                        .path("items")
                        .path(0)))
                .isCloseTo(10.0, within(0.05));
        assertThat(distance(ok(api.get("/api/v1/search?types=gigs&q=here " + tag + "&" + from, fan))
                        .path("gigs")
                        .path(0)))
                .isCloseTo(10.0, within(0.05));
        assertThat(api.put("/api/v1/gigs/" + gigId + "/attendance", Map.of("status", "GOING"), fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(distance(ok(api.get("/api/v1/me/gigs?" + from, fan))
                        .path("items")
                        .path(0)))
                .isCloseTo(10.0, within(0.05));

        // Without a position nothing changes: no home city here, so no distance at all.
        assertThat(ok(api.get("/api/v1/gigs/" + gigId, fan))
                        .path("gig")
                        .path("distanceKm")
                        .isNull())
                .isTrue();
        assertThat(api.get("/api/v1/gigs/" + gigId + "?fromLat=63.9", fan)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("LOCATION_INCOMPLETE");
        assertThat(api.get("/api/v1/gigs/" + gigId + "?fromLat=95&fromLng=0", fan)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("VALIDATION_FAILED");
    }

    private static double distance(JsonNode gig) {
        assertThat(gig.path("distanceKm").isNumber()).as(gig.toString()).isTrue();
        return gig.path("distanceKm").asDouble();
    }

    @Test
    void gigDetailAndIdempotentAttendance() {
        double lat = 62.40, lng = -0.60;
        SourceVenue venue = venue("detail", lat, lng);
        UUID gigId = gig(
                "detail",
                base,
                venue,
                new SourceArtist("a-head-" + tag, "Headliner " + tag, null, List.of("Rock"), null),
                new SourceArtist("a-supp-" + tag, "Support " + tag, null, List.of("Folk"), null));
        UUID pastGig = gig("was", Instant.now().minus(Duration.ofDays(3)), venue, "Earlier Act");
        TestApi.User other = api.register("other");

        JsonNode detail = ok(api.get("/api/v1/gigs/" + gigId, fan));
        assertThat(detail.path("gig").path("title").asText("")).isEqualTo("detail " + tag);
        assertThat(detail.path("gig").path("headliner").path("name").asText("")).isEqualTo("Headliner " + tag);
        assertThat(detail.path("gig").path("headliner").path("genres").path(0).asText(""))
                .isEqualTo("rock");
        assertThat(detail.path("gig").path("support").path(0).path("name").asText(""))
                .isEqualTo("Support " + tag);
        assertThat(detail.path("gig").path("venue").path("location").path("lat").asDouble())
                .isCloseTo(lat, within(1e-6));
        assertThat(detail.path("gig").path("priceMin").asDouble()).isEqualTo(12.5);
        assertThat(detail.path("gig").path("ticketUrl").asText("")).startsWith("https://tickets.example.com/");
        assertThat(detail.path("gig").path("myAttendance").isNull()).isTrue();
        assertThat(detail.path("lineup").size()).isEqualTo(2);
        assertThat(detail.path("sources").path(0).path("source").asText("")).isEqualTo("TICKETMASTER");
        assertThat(detail.path("attendanceCounts").path("going").asInt()).isZero();
        assertThat(detail.path("postsCount").asInt()).isZero();
        assertThat(api.get("/api/v1/gigs/" + UUID.randomUUID(), fan)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("GIG_NOT_FOUND");

        // PUT twice → one row; changing status moves the count; another user adds to it.
        String path = "/api/v1/gigs/" + gigId + "/attendance";
        assertThat(ok(api.put(path, Map.of("status", "GOING"), fan))
                        .path("attendanceCounts")
                        .path("going")
                        .asInt())
                .isEqualTo(1);
        JsonNode again = ok(api.put(path, Map.of("status", "GOING"), fan));
        assertThat(again.path("attendanceCounts").path("going").asInt()).isEqualTo(1);
        assertThat(again.path("myAttendance").asText("")).isEqualTo("GOING");
        assertThat(dsl.fetchValue("select count(*) from catalog.gig_attendance where gig_id = ?", gigId))
                .isEqualTo(1L);
        ok(api.put(path, Map.of("status", "GOING"), other));
        JsonNode switched = ok(api.put(path, Map.of("status", "INTERESTED"), fan));
        assertThat(switched.path("attendanceCounts").path("going").asInt()).isEqualTo(1);
        assertThat(switched.path("attendanceCounts").path("interested").asInt()).isEqualTo(1);

        assertThat(api.put(path, Map.of("status", "MAYBE"), fan)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("ATTENDANCE_INVALID");
        assertThat(api.put("/api/v1/gigs/" + UUID.randomUUID() + "/attendance", Map.of("status", "GOING"), fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // myAttendance shows in lists; /me/gigs splits upcoming and past.
        assertThat(ok(api.get("/api/v1/gigs?lat=" + lat + "&lng=" + lng, fan))
                        .path("items")
                        .path(0)
                        .path("myAttendance")
                        .asText(""))
                .isEqualTo("INTERESTED");
        ok(api.put("/api/v1/gigs/" + pastGig + "/attendance", Map.of("status", "WENT"), fan));
        assertThat(titles(ok(api.get("/api/v1/me/gigs", fan)))).containsExactly("detail " + tag);
        assertThat(titles(ok(api.get("/api/v1/me/gigs?when=past", fan)))).containsExactly("was " + tag);
        assertThat(titles(ok(api.get("/api/v1/me/gigs?status=GOING", fan)))).isEmpty();
        assertThat(titles(ok(api.get("/api/v1/me/gigs?status=INTERESTED", fan))))
                .containsExactly("detail " + tag);
        assertThat(api.get("/api/v1/me/gigs?when=someday", fan)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("WHEN_INVALID");

        // DELETE twice → both succeed; the other user's mark stays.
        assertThat(api.delete(path, fan).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.delete(path, fan).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        JsonNode after = ok(api.get("/api/v1/gigs/" + gigId, fan));
        assertThat(after.path("attendanceCounts").path("interested").asInt()).isZero();
        assertThat(after.path("attendanceCounts").path("going").asInt()).isEqualTo(1);
        assertThat(after.path("gig").path("myAttendance").isNull()).isTrue();
    }

    @Test
    void artistsSearchFollowAndDetail() {
        double lat = 62.90, lng = -0.50;
        SourceVenue venue = venue("artists", lat, lng);
        String name = "Zephyrine Quartet " + tag;
        for (int i = 0; i < 7; i++) {
            gig(
                    "tour" + i,
                    base.plus(Duration.ofDays(i)),
                    venue,
                    new SourceArtist("a-zq-" + tag, name, null, List.of("Jazz"), null));
        }
        UUID artistId = UUID.fromString(ok(api.get("/api/v1/gigs?lat=" + lat + "&lng=" + lng + "&limit=1", fan))
                .path("items")
                .path(0)
                .path("headliner")
                .path("id")
                .asText(""));

        // Prefix, a typo, accents and case all find it.
        for (String q : List.of("zephyrine quartet " + tag, "Zephyrin Quartet " + tag, "ZÉPHYRINE quartet " + tag)) {
            JsonNode found = ok(api.get("/api/v1/artists?q=" + q, fan));
            assertThat(found.path("items").path(0).path("id").asText("")).as(q).isEqualTo(artistId.toString());
        }
        assertThat(ok(api.get("/api/v1/artists?q=qqqqzzzz" + tag, fan))
                        .path("items")
                        .size())
                .isZero();
        // Popular list (onboarding): bounded, every entry has a name.
        JsonNode popular = ok(api.get("/api/v1/artists?popular=true&limit=5", fan));
        assertThat(popular.path("items").size()).isBetween(1, 5);
        // In the genres just picked: Jazz (17) suggests it and only jazz acts; Techno (14)
        // does not suggest it; Rock (1) counts its children, so an Indie act is suggested for it.
        JsonNode jazz = ok(api.get("/api/v1/artists?popular=true&genreIds=17&limit=50", fan));
        assertThat(jazz.path("items").valueStream().map(a -> a.path("id").asText("")))
                .contains(artistId.toString());
        assertThat(jazz.path("items").valueStream())
                .allSatisfy(a -> assertThat(a.path("genres").valueStream().map(value -> value.asText("")))
                        .contains("jazz"));
        assertThat(ok(api.get("/api/v1/artists?popular=true&genreIds=14&limit=50", fan))
                        .path("items")
                        .valueStream()
                        .map(a -> a.path("id").asText("")))
                .doesNotContain(artistId.toString());
        String indieName = "Paper Lanterns " + tag;
        gig("indie", base, venue, new SourceArtist("a-pl-" + tag, indieName, null, List.of("Indie"), null));
        assertThat(ok(api.get("/api/v1/artists?popular=true&genreIds=1&genreIds=17&limit=50", fan))
                        .path("items")
                        .valueStream()
                        .map(a -> a.path("name").asText("")))
                .contains(indieName, name);

        // Detail: genres, follower count, the next five gigs only.
        JsonNode detail = ok(api.get("/api/v1/artists/" + artistId, fan));
        assertThat(detail.path("artist").path("name").asText("")).isEqualTo(name);
        assertThat(detail.path("artist").path("genres").path(0).asText("")).isEqualTo("jazz");
        assertThat(detail.path("artist").path("isFollowing").asBoolean()).isFalse();
        assertThat(detail.path("followerCount").asInt()).isZero();
        assertThat(detail.path("upcomingGigs").size()).isEqualTo(5);
        assertThat(ok(api.get("/api/v1/artists/" + artistId + "/gigs?limit=50", fan))
                        .path("items")
                        .size())
                .isEqualTo(7);
        assertThat(api.get("/api/v1/artists/" + UUID.randomUUID(), fan)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("ARTIST_NOT_FOUND");

        // Follow twice → one follow; unfollow twice → none.
        String follow = "/api/v1/artists/" + artistId + "/follow";
        assertThat(api.put(follow, null, fan).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.put(follow, null, fan).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        JsonNode followed = ok(api.get("/api/v1/artists/" + artistId, fan));
        assertThat(followed.path("artist").path("isFollowing").asBoolean()).isTrue();
        assertThat(followed.path("followerCount").asInt()).isEqualTo(1);
        assertThat(ok(api.get("/api/v1/gigs?lat=" + lat + "&lng=" + lng + "&limit=1", fan))
                        .path("items")
                        .path(0)
                        .path("headliner")
                        .path("isFollowing")
                        .asBoolean())
                .isTrue();
        assertThat(api.put("/api/v1/artists/" + UUID.randomUUID() + "/follow", null, fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.delete(follow, fan).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.delete(follow, fan).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(ok(api.get("/api/v1/artists/" + artistId, fan))
                        .path("followerCount")
                        .asInt())
                .isZero();

        // The service reports whether a follow was new (the event is published only then).
        assertThat(follows.follow(fan.id(), artistId)).isTrue();
        assertThat(follows.follow(fan.id(), artistId)).isFalse();
    }

    @Test
    void venuesAndSearch() {
        double lat = 63.40, lng = -0.40;
        SourceVenue venue = new SourceVenue(
                "v-venue-" + tag, "The Lighthouse " + tag, "1 Pier Road", "Unst", "ZE2 9AA", lat, lng, "Europe/London");
        String act = "Searchable Act " + tag;
        UUID gigId =
                gig("venue-gig", base, venue, new SourceArtist("a-search-" + tag, act, null, List.of("Pop"), null));
        for (int i = 0; i < 6; i++) {
            gig(
                    "more" + i,
                    base.plus(Duration.ofDays(i + 1)),
                    venue,
                    new SourceArtist("a-search-" + tag, act, null, List.of("Pop"), null));
        }
        String venueId = ok(api.get("/api/v1/gigs/" + gigId, fan))
                .path("gig")
                .path("venue")
                .path("id")
                .asText("");

        JsonNode detail = ok(api.get("/api/v1/venues/" + venueId, fan));
        assertThat(detail.path("name").asText("")).isEqualTo("The Lighthouse " + tag);
        assertThat(detail.path("address").asText("")).isEqualTo("1 Pier Road");
        assertThat(detail.path("postcode").asText("")).isEqualTo("ZE2 9AA");
        assertThat(detail.path("location").path("lng").asDouble()).isCloseTo(lng, within(1e-6));
        assertThat(detail.path("timezone").asText("")).isEqualTo("Europe/London");
        assertThat(ok(api.get("/api/v1/venues/" + venueId + "/gigs?limit=50", fan))
                        .path("items")
                        .size())
                .isEqualTo(7);
        assertThat(api.get("/api/v1/venues/" + UUID.randomUUID(), fan).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // The gig list's text filter also matches the venue's name.
        assertThat(ok(api.get("/api/v1/gigs?radiusKm=5&lat=" + lat + "&lng=" + lng + "&q=lighthouse " + tag, fan))
                        .path("items")
                        .size())
                .isEqualTo(7);

        // Search: grouped, at most five per type, types selectable.
        JsonNode both = ok(api.get("/api/v1/search?q=searchable act " + tag, fan));
        assertThat(both.path("artists").path(0).path("name").asText("")).isEqualTo(act);
        assertThat(both.path("gigs").size()).isEqualTo(5);
        JsonNode artistsOnly = ok(api.get("/api/v1/search?q=searchable act " + tag + "&types=artists", fan));
        assertThat(artistsOnly.path("artists").size()).isEqualTo(1);
        assertThat(artistsOnly.path("gigs").size()).isZero();
        assertThat(api.get("/api/v1/search?q=x&types=venues", fan)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("SEARCH_TYPE_UNKNOWN");
        assertThat(api.get("/api/v1/search?q=", fan).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    @Test
    void suggestionsPutTheExactNameFirstThenTheViewer() {
        // "The North" listed "North Base" and "North Shared Lounge" above "the north" (the
        // normaliser drops "the", all three start with "north", and the tie was broken by name).
        String word = "North" + tag;
        SourceVenue hall = venue("north", 61.0, -1.0);
        gig("nb", base, hall, new SourceArtist("a-nb-" + tag, word + " Base", null, List.of("Jazz"), null));
        gig(
                "ns",
                base.plus(Duration.ofDays(1)),
                hall,
                new SourceArtist("a-ns-" + tag, word + " Shared Lounge", null, List.of("Jazz"), null));
        String theNorth = "the " + word.toLowerCase(java.util.Locale.ROOT);
        gig(
                "tn",
                base.plus(Duration.ofDays(2)),
                hall,
                new SourceArtist("a-tn-" + tag, theNorth, null, List.of("Jazz"), null));
        SourceVenue iwm =
                new SourceVenue("v-iwm-" + tag, "IWM " + word, null, "Salford", null, 61.1, -1.1, "Europe/London");
        gig("iwm", base, iwm, "Someone");

        JsonNode typed = ok(api.get("/api/v1/search/suggest?q=The " + word, fan));
        // (Further down, only similar names: the test venue "Venue north <tag>".)
        assertThat(names(typed)).startsWith(theNorth, word + " Base", word + " Shared Lounge", "IWM " + word);
        assertThat(typed.path(3).path("kind").asText("")).isEqualTo("VENUE");
        assertThat(typed.path(3).path("city").asText("")).isEqualTo("Salford");
        // Without "the", the same act is still the exact match.
        assertThat(names(ok(api.get("/api/v1/search/suggest?q=" + word, fan))).getFirst())
                .isEqualTo(theNorth);

        // Then the viewer: an act they follow comes first among the equally good matches.
        String lounge = typed.path(2).path("id").asText("");
        assertThat(api.put("/api/v1/artists/" + lounge + "/follow", null, fan).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(names(ok(api.get("/api/v1/search/suggest?q=The " + word, fan))))
                .startsWith(theNorth, word + " Shared Lounge", word + " Base", "IWM " + word);

        assertThat(api.get("/api/v1/search/suggest?q=n", fan).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static List<String> names(JsonNode suggestions) {
        return suggestions.valueStream().map(s -> s.path("name").asText("")).toList();
    }

    private SourceVenue venue(String key, double lat, double lng) {
        return new SourceVenue(
                "v-" + key + "-" + tag, "Venue " + key + " " + tag, null, "Testville", null, lat, lng, "Europe/London");
    }

    private UUID gig(String title, Instant startsAt, SourceVenue venue, String headliner) {
        return gig(
                title,
                startsAt,
                venue,
                new SourceArtist("a-" + title + "-" + tag, headliner + " " + tag, null, List.of("Rock"), null));
    }

    /** Stores a gig through the real ingestion path and returns its catalog id. */
    private UUID gig(String title, Instant startsAt, SourceVenue venue, SourceArtist... artists) {
        String externalId = "g-" + title + "-" + tag;
        resolver.ingest(
                new SourceGig(
                        "TICKETMASTER",
                        externalId,
                        "https://tickets.example.com/" + externalId,
                        title + " " + tag,
                        startsAt,
                        null,
                        "SCHEDULED",
                        "https://tickets.example.com/" + externalId,
                        new BigDecimal("12.50"),
                        new BigDecimal("20.00"),
                        "GBP",
                        null,
                        venue,
                        List.of(artists)),
                new RunStats(),
                Instant.now());
        return dsl.fetchOne(
                        "select gig_id from catalog.gig_source where source = 'TICKETMASTER' and external_id = ?",
                        externalId)
                .get(0, UUID.class);
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static List<String> titles(JsonNode page) {
        List<String> titles = new ArrayList<>();
        page.path("items").forEach(item -> titles.add(item.path("title").asText("")));
        return titles;
    }
}
