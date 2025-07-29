package app.soundcheck.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.social.internal.GigAlertService;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * A profile lists the artists and venues it follows (privacy as for follower lists, the
 * level only to the owner), and each follow's level decides whether its new gigs and new posts reach
 * the follower; new posts are rolled up into one entry per artist (or venue) per hour.
 */
class FollowListsAndLevelsIT extends IntegrationTest {

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
    GigAlertService alerts;

    @Autowired
    Clock clock;

    TestApi api;
    TestData data;
    TestApi.User fan;
    TestApi.User other;
    String tag;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        fan = api.register("lister");
        other = api.register("looker");
        tag = UUID.randomUUID().toString().substring(0, 8);
        alerts.run(); // starts alerts, or deals with what other tests created
    }

    // ── Followed artists and venues ─────────────────────────────────────────────────────────────

    @Test
    void aProfileListsItsFollowedArtistsAndVenuesNewestFirstWithTheLevelOnlyForItsOwner() {
        UUID keo = data.artist("Keo " + tag);
        UUID tooth = data.artist("Tooth " + tag);
        UUID venue = data.venue("Night People " + tag);
        noContent(api.put("/api/v1/artists/" + keo + "/follow", null, fan));
        noContent(api.put("/api/v1/artists/" + tooth + "/follow", null, fan));
        noContent(api.put("/api/v1/venues/" + venue + "/follow", null, fan));
        noContent(api.put("/api/v1/artists/" + tooth + "/follow", null, other));

        String artists = "/api/v1/users/" + fan.id() + "/following/artists";
        JsonNode own = ok(api.get(artists, fan));
        assertThat(names(own, "artist")).containsExactly("Tooth " + tag, "Keo " + tag);
        assertThat(own.path("items").get(0).path("notifyLevel").asText("")).isEqualTo("GIGS");
        assertThat(own.path("items").get(0).path("artist").path("isFollowing").asBoolean())
                .isTrue();

        // Someone else sees the list, whether they follow each artist too, and no levels.
        JsonNode seen = ok(api.get(artists, other));
        assertThat(seen.path("items").get(0).path("notifyLevel").isNull()).isTrue();
        assertThat(seen.path("items").get(0).path("artist").path("isFollowing").asBoolean())
                .isTrue();
        assertThat(seen.path("items").get(1).path("artist").path("isFollowing").asBoolean())
                .isFalse();

        // Paged with a keyset cursor.
        JsonNode first = ok(api.get(artists + "?limit=1", other));
        assertThat(names(first, "artist")).containsExactly("Tooth " + tag);
        JsonNode second = ok(
                api.get(artists + "?limit=1&cursor=" + first.path("nextCursor").asText(""), other));
        assertThat(names(second, "artist")).containsExactly("Keo " + tag);
        assertThat(second.path("nextCursor").isNull()).isTrue();

        JsonNode venues = ok(api.get("/api/v1/users/" + fan.id() + "/following/venues", other));
        assertThat(names(venues, "venue")).containsExactly("Night People " + tag);
        assertThat(venues.path("items").get(0).path("isFollowing").asBoolean()).isFalse();
        assertThat(ok(api.get("/api/v1/users/" + fan.id() + "/following/venues", fan))
                        .path("items")
                        .get(0)
                        .path("isFollowing")
                        .asBoolean())
                .isTrue();

        // The header counts the artists.
        assertThat(ok(api.get("/api/v1/users/" + fan.handle(), other))
                        .path("counts")
                        .path("artists")
                        .asLong())
                .isEqualTo(2);

        // A private profile's lists are for its followers; a blocker's do not exist.
        ok(api.patch("/api/v1/me", Map.of("profileVisibility", "PRIVATE"), fan));
        assertThat(code(api.get(artists, other))).isEqualTo("PROFILE_PRIVATE");
        assertThat(code(api.get("/api/v1/users/" + fan.id() + "/following/venues", other)))
                .isEqualTo("PROFILE_PRIVATE");
        assertThat(ok(api.get(artists, fan)).path("items").size()).isEqualTo(2);
        noContent(api.put("/api/v1/users/" + other.id() + "/block", null, fan));
        assertThat(code(api.get(artists, other))).isEqualTo("USER_NOT_FOUND");
    }

    // ── Levels ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void aFollowersLevelIsSetOnTheFollowAndShownOnTheArtistAndVenuePages() {
        UUID keo = data.artist("Keo " + tag);
        UUID venue = data.venue("Brudenell " + tag);
        String artistPath = "/api/v1/artists/" + keo;
        assertThat(ok(api.get(artistPath, fan)).path("notifyLevel").isNull()).isTrue();
        assertThat(code(api.put(artistPath + "/follow/notify", Map.of("level", "ALL"), fan)))
                .isEqualTo("NOT_FOLLOWING");

        noContent(api.put(artistPath + "/follow", null, fan));
        assertThat(ok(api.get(artistPath, fan)).path("notifyLevel").asText("")).isEqualTo("GIGS");
        noContent(api.put(artistPath + "/follow/notify", Map.of("level", "ALL"), fan));
        assertThat(ok(api.get(artistPath, fan)).path("notifyLevel").asText("")).isEqualTo("ALL");
        assertThat(ok(api.get(artistPath, other)).path("notifyLevel").isNull()).isTrue();
        assertThat(api.put(artistPath + "/follow/notify", Map.of("level", "LOUD"), fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // Following again keeps the level; unfollowing forgets it.
        noContent(api.put(artistPath + "/follow", null, fan));
        assertThat(ok(api.get(artistPath, fan)).path("notifyLevel").asText("")).isEqualTo("ALL");
        noContent(api.delete(artistPath + "/follow", fan));
        noContent(api.put(artistPath + "/follow", null, fan));
        assertThat(ok(api.get(artistPath, fan)).path("notifyLevel").asText("")).isEqualTo("GIGS");

        String venuePath = "/api/v1/venues/" + venue;
        noContent(api.put(venuePath + "/follow", null, fan));
        noContent(api.put(venuePath + "/follow/notify", Map.of("level", "NONE"), fan));
        JsonNode page = ok(api.get(venuePath, fan));
        assertThat(page.path("following").asBoolean()).isTrue();
        assertThat(page.path("notifyLevel").asText("")).isEqualTo("NONE");
    }

    @Test
    void onlyGigsAndAllLevelsHearAboutNewGigs() {
        UUID keo = data.artist("Keo " + tag);
        UUID venue = data.venue("Moth " + tag);
        TestApi.User posts = api.register("postsonly");
        TestApi.User all = api.register("all");
        for (TestApi.User u : List.of(fan, posts, all)) {
            noContent(api.put("/api/v1/artists/" + keo + "/follow", null, u));
        }
        noContent(api.put("/api/v1/artists/" + keo + "/follow/notify", Map.of("level", "POSTS"), posts));
        noContent(api.put("/api/v1/artists/" + keo + "/follow/notify", Map.of("level", "ALL"), all));

        data.gig("Keo " + tag, venue, Instant.now(clock).plus(Duration.ofDays(9)), keo);
        alerts.run();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(types(fan)).contains("NEW_GIGS");
            assertThat(types(all)).contains("NEW_GIGS");
        });
        assertThat(types(posts)).doesNotContain("NEW_GIGS");
    }

    // ── New posts ───────────────────────────────────────────────────────────────────────────────

    @Test
    void newPostsReachPostsAndAllFollowersOncePerArtistRolledUpWithinTheHour() {
        TestApi.User author = api.register("poster");
        TestApi.User artistFan = api.register("artistfan");
        TestApi.User venueFan = api.register("venuefan");
        TestApi.User both = api.register("both");
        TestApi.User gigsOnly = api.register("gigsonly");
        UUID keo = data.artist("Keo " + tag);
        UUID venue = data.venue("Band on the Wall " + tag);
        UUID gig = data.gig("Keo " + tag, venue, Instant.now(clock).minus(Duration.ofDays(2)), keo);
        follow("artists", keo, artistFan, "POSTS");
        follow("venues", venue, venueFan, "ALL");
        follow("artists", keo, both, "ALL");
        follow("venues", venue, both, "POSTS");
        follow("artists", keo, gigsOnly, null);
        follow("artists", keo, author, "ALL"); // the author is never told about their own post

        String first = post(author, gig);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(newPosts(artistFan)).hasSize(1);
            assertThat(newPosts(venueFan)).hasSize(1);
            assertThat(newPosts(both)).hasSize(1);
        });
        JsonNode entry = newPosts(artistFan).getFirst();
        assertThat(entry.path("type").asText("")).isEqualTo("NEW_POSTS_ARTIST");
        assertThat(entry.path("subjectId").asText("")).isEqualTo(keo.toString());
        assertThat(entry.path("subjectName").asText("")).isEqualTo("Keo " + tag);
        assertThat(entry.path("postCount").asInt()).isEqualTo(1);
        assertThat(entry.path("postId").asText("")).isEqualTo(first);
        JsonNode atVenue = newPosts(venueFan).getFirst();
        assertThat(atVenue.path("type").asText("")).isEqualTo("NEW_POSTS_VENUE");
        assertThat(atVenue.path("subjectName").asText("")).isEqualTo("Band on the Wall " + tag);
        // Following the act and the venue: told once, for the act.
        assertThat(newPosts(both).getFirst().path("type").asText("")).isEqualTo("NEW_POSTS_ARTIST");

        // A second post of the act within the hour (another night: one post per gig) is counted
        // in the same entry, which points at the newest post.
        UUID nextNight = data.gig(
                "Keo again " + tag,
                data.venue("Ritz " + tag),
                Instant.now(clock).minus(Duration.ofDays(1)),
                keo);
        String second = post(author, nextNight);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                        newPosts(artistFan).getFirst().path("postCount").asInt())
                .isEqualTo(2));
        assertThat(newPosts(artistFan)).hasSize(1);
        assertThat(newPosts(artistFan).getFirst().path("postId").asText("")).isEqualTo(second);

        assertThat(newPosts(gigsOnly)).isEmpty();
        assertThat(newPosts(author)).isEmpty();
    }

    @Test
    void aPrivateAuthorsPostsReachOnlyTheirFollowersAndNobodyWithABlock() {
        TestApi.User author = api.register("hidden");
        TestApi.User follower = api.register("insider");
        TestApi.User stranger = api.register("outsider");
        TestApi.User blocked = api.register("blocked");
        UUID keo = data.artist("Keo " + tag);
        UUID gig = data.gig(
                "Keo " + tag, data.venue("Hall " + tag), Instant.now(clock).minus(Duration.ofDays(3)), keo);
        for (TestApi.User u : List.of(follower, stranger, blocked)) {
            follow("artists", keo, u, "POSTS");
        }
        noContent(api.put("/api/v1/users/" + blocked.id() + "/block", null, author));
        // A public author: everyone but the blocked fan hears about it.
        post(author, gig);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(newPosts(stranger)).hasSize(1));
        assertThat(newPosts(blocked)).isEmpty();

        // Private now: only the author's follower hears about a new post (of another act, so the first
        // act's roll-up within the hour cannot hide it).
        UUID tooth = data.artist("Tooth " + tag);
        UUID gig2 = data.gig(
                "Tooth " + tag, data.venue("Room " + tag), Instant.now(clock).minus(Duration.ofDays(3)), tooth);
        for (TestApi.User u : List.of(follower, stranger)) {
            follow("artists", tooth, u, "POSTS");
        }
        api.put("/api/v1/users/" + author.id() + "/follow", null, follower);
        ok(api.patch("/api/v1/me", Map.of("profileVisibility", "PRIVATE"), author));
        post(author, gig2);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(subjects(follower)).contains(tooth.toString()));
        assertThat(subjects(stranger)).doesNotContain(tooth.toString());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private void follow(String kind, UUID id, TestApi.User user, String level) {
        noContent(api.put("/api/v1/" + kind + "/" + id + "/follow", null, user));
        if (level != null) {
            noContent(api.put("/api/v1/" + kind + "/" + id + "/follow/notify", Map.of("level", level), user));
        }
    }

    /** A post with one ready clip at the gig: it goes live at once. */
    private String post(TestApi.User author, UUID gigId) {
        Map<String, Object> clip = new HashMap<>();
        clip.put("mediaId", data.video(author.id(), "READY"));
        ResponseEntity<JsonNode> created =
                api.post("/api/v1/posts", Map.of("clips", List.of(clip), "gigId", gigId), author);
        assertThat(created.getStatusCode())
                .as(String.valueOf(created.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        return created.getBody().path("id").asText("");
    }

    private List<JsonNode> newPosts(TestApi.User user) {
        List<JsonNode> items = new ArrayList<>();
        ok(api.get("/api/v1/notifications", user)).path("items").forEach(n -> {
            if (n.path("type").asText("").startsWith("NEW_POSTS")) {
                items.add(n);
            }
        });
        return items;
    }

    private List<String> subjects(TestApi.User user) {
        return newPosts(user).stream().map(n -> n.path("subjectId").asText("")).toList();
    }

    private List<String> types(TestApi.User user) {
        List<String> types = new ArrayList<>();
        ok(api.get("/api/v1/notifications", user))
                .path("items")
                .forEach(n -> types.add(n.path("type").asText("")));
        return types;
    }

    private static List<String> names(JsonNode page, String field) {
        List<String> names = new ArrayList<>();
        page.path("items").forEach(i -> names.add(i.path(field).path("name").asText("")));
        return names;
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static void noContent(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    private static String code(ResponseEntity<JsonNode> response) {
        return response.getBody().path("code").asText("");
    }
}
