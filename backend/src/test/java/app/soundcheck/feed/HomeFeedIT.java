package app.soundcheck.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.feed.internal.RankingConfigs;
import app.soundcheck.feed.internal.ranking.RankingConfig;
import app.soundcheck.identity.internal.AccountPurgeService;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.MutableClock;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The home feed is built once per session and paged in a stable order without
 * repeats; every served item is logged with the score and features it was ranked on; activating another
 * config changes the next session and not the one being paged; a missing, expired or foreign session is
 * 410; score details are for admins only.
 *
 * <p>The database is shared with the other tests, so the feed also holds their posts. The tests here
 * therefore compare the API's answers with what the session and the served-item log hold, rather than
 * with a fixed list.
 */
class HomeFeedIT extends IntegrationTest {

    static final UUID SEED_CONFIG = UUID.fromString("00000000-0000-7000-8000-000000000001");

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
    RankingConfigs configs;

    @Autowired
    AccountPurgeService purge;

    @Autowired
    MutableClock clock;

    @Autowired
    JsonMapper json;

    TestApi api;
    TestData data;
    TestApi.User viewer;
    TestApi.User followed;
    List<TestApi.User> authors;
    List<UUID> myPosts;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        viewer = api.register("feedviewer");
        followed = api.register("feedfollowed");
        api.put("/api/v1/users/" + followed.id() + "/follow", null, viewer);
        // Thirty posts by six authors, spread over the last two days. The viewer follows all six, so the
        // posts are candidates by construction (FOLLOWING: 300 posts, 14 days), however many posts other
        // tests have left in the fresh and trending pools of the shared database.
        List<TestApi.User> authors = new ArrayList<>(List.of(followed));
        for (int i = 0; i < 5; i++) {
            TestApi.User author = api.register("feedauthor");
            api.put("/api/v1/users/" + author.id() + "/follow", null, viewer);
            authors.add(author);
        }
        this.authors = authors;
        myPosts = new ArrayList<>();
        Instant now = Instant.now(clock);
        for (int i = 0; i < 30; i++) {
            TestApi.User author = authors.get(i % authors.size());
            UUID post = UUID.fromString(api.post(
                            "/api/v1/posts",
                            Map.of("clips", List.of(Map.of("mediaId", data.video(author.id(), "READY")))),
                            author)
                    .getBody()
                    .path("id")
                    .asText(""));
            dsl.execute(
                    "update content.post set published_at = ?::timestamptz where id = ?",
                    now.minus(Duration.ofMinutes(90L * i)).toString(),
                    post);
            myPosts.add(post);
        }
    }

    @AfterEach
    void restore() {
        clock.reset();
        // Other tests expect the seeded config to be the only one, and ACTIVE.
        dsl.execute(
                "update feed.ranking_config set status = 'ARCHIVED' where status = 'ACTIVE' and id <> ?", SEED_CONFIG);
        dsl.execute("update feed.ranking_config set status = 'ACTIVE' where id = ?", SEED_CONFIG);
        dsl.execute("delete from feed.ranking_config where id <> ?", SEED_CONFIG);
        configs.reload();
    }

    @Test
    void aSessionIsBuiltOnceAndPagedInAStableOrderWithoutRepeats() {
        JsonNode first = home("?limit=10", viewer);
        String sessionId = first.path("sessionId").asText("");
        assertThat(first.path("items").size()).isEqualTo(10);
        assertThat(first.path("nextCursor").asText("")).isEqualTo(sessionId + ":10");

        // Things change while the user scrolls: a new post, and one of the later posts gets very popular.
        TestApi.User late = api.register("latecomer");
        api.post("/api/v1/posts", Map.of("clips", List.of(Map.of("mediaId", data.video(late.id(), "READY")))), late);
        dsl.execute(
                "insert into signals.post_stats (post_id, impressions, likes) values (?, 10, 10)"
                        + " on conflict (post_id) do update set likes = 10000",
                myPosts.get(29));

        List<String> served = new ArrayList<>(postIds(first));
        List<Integer> positions = new ArrayList<>(positions(first));
        String cursor = first.path("nextCursor").asText("");
        int pages = 1;
        while (cursor != null && pages < 30) {
            JsonNode page = home("?limit=10&cursor=" + cursor, viewer);
            assertThat(page.path("sessionId").asText("")).isEqualTo(sessionId);
            served.addAll(postIds(page));
            positions.addAll(positions(page));
            cursor = page.path("nextCursor").isNull()
                    ? null
                    : page.path("nextCursor").asText("");
            pages++;
        }

        // Every post once, in the order the session stored when it was built.
        assertThat(served).doesNotHaveDuplicates();
        assertThat(served).containsAll(myPosts.stream().map(UUID::toString).toList());
        List<String> stored = sessionPostIds(sessionId);
        assertThat(served).containsExactlyElementsOf(stored);
        assertThat(positions).isSorted().doesNotHaveDuplicates();
        assertThat(positions.getFirst()).isZero();
        assertThat(positions.getLast()).isEqualTo(stored.size() - 1);
        assertThat(pages).isEqualTo((stored.size() + 9) / 10);

        // A request without a cursor is a new session (pull to refresh).
        assertThat(home("?limit=10", viewer).path("sessionId").asText("")).isNotEqualTo(sessionId);
    }

    @Test
    void everyServedItemIsLoggedWithTheScoreAndFeaturesItWasRankedOn() {
        JsonNode page = home("?limit=7", viewer);
        String sessionId = page.path("sessionId").asText("");

        Result<Record> log = dsl.fetch(
                "select position, user_id, post_id, config_id, score, features, exploration"
                        + " from feed.served_item where session_id = ? order by position",
                UUID.fromString(sessionId));
        JsonNode stored = sessionItems(sessionId);

        assertThat(log).hasSize(7);
        for (int i = 0; i < 7; i++) {
            Record row = log.get(i);
            JsonNode item = stored.get(i);
            assertThat(row.get("position", Integer.class)).isEqualTo(i);
            assertThat(row.get("user_id", UUID.class)).isEqualTo(viewer.id());
            assertThat(row.get("config_id", UUID.class)).isEqualTo(SEED_CONFIG);
            assertThat(row.get("post_id", UUID.class).toString())
                    .isEqualTo(item.path("p").asText(""))
                    .isEqualTo(page.path("items").get(i).path("post").path("id").asText(""));
            assertThat(row.get("score", Float.class).doubleValue())
                    .isCloseTo(item.path("s").asDouble(), within(1e-6));
            assertThat(row.get("exploration", Boolean.class))
                    .isEqualTo(item.path("x").asBoolean());
            Float[] features = row.get("features", Float[].class);
            assertThat(features).hasSize(12);
            for (int f = 0; f < 12; f++) {
                assertThat(features[f].doubleValue())
                        .isCloseTo(item.path("f").get(f).asDouble(), within(1e-6));
            }
        }
        // The second page is logged when it is served, not before.
        home("?limit=7&cursor=" + page.path("nextCursor").asText(""), viewer);
        assertThat(dsl.fetchCount(
                        dsl.selectFrom("feed.served_item").where("session_id = ?", UUID.fromString(sessionId))))
                .isEqualTo(14);
    }

    @Test
    void everyItemSaysWhyItIsThereAndAFollowedAuthorsPostSaysSo() {
        JsonNode page = home("?limit=50", viewer);

        boolean sawFollowed = false;
        for (JsonNode item : page.path("items")) {
            assertThat(item.path("reasons").size()).isBetween(1, 2);
            assertThat(item.path("debug").isNull()).isTrue();
            if (item.path("post").path("author").path("handle").asText("").equals(followed.handle())) {
                sawFollowed = true;
                boolean exploration =
                        item.path("reasons").get(0).path("kind").asText("").equals("NEW_FOR_YOU");
                if (!exploration) {
                    assertThat(item.path("reasons").get(0).path("kind").asText(""))
                            .isEqualTo("FOLLOWS_AUTHOR");
                    assertThat(item.path("reasons").get(0).path("text").asText(""))
                            .isEqualTo("You follow @" + followed.handle());
                }
            }
        }
        assertThat(sawFollowed).isTrue();
    }

    @Test
    void activatingAnotherConfigChangesTheNextSessionAndNotTheOneBeingPaged() {
        JsonNode before = home("?limit=5", viewer);
        String oldSession = before.path("sessionId").asText("");

        // A config that ranks by age alone, oldest first: no diversity, no exploration.
        Map<String, Double> weights = new HashMap<>();
        for (String name : List.of(
                "bias",
                "follows_author",
                "follows_artist",
                "artist_affinity",
                "author_affinity",
                "genre_similarity",
                "gig_relation",
                "proximity",
                "engagement_quality",
                "social_proof",
                "seen",
                "position_prior")) {
            weights.put(name, 0.0);
        }
        weights.put("recency", -8.0);
        UUID draft = configs.createDraft(weights, params(1.0, 0.0), "MANUAL", "oldest first (test)", null);
        tx.executeWithoutResult(status -> configs.activate(draft));
        assertThat(configs.active().id()).isEqualTo(draft);
        assertThat(dsl.fetchSingle("select status from feed.ranking_config where id = ?", SEED_CONFIG)
                        .get(0, String.class))
                .isEqualTo("ARCHIVED");

        // The session being paged goes on exactly as it was built, with the config it was built with.
        JsonNode next = home("?limit=5&cursor=" + before.path("nextCursor").asText(""), viewer);
        assertThat(postIds(next))
                .containsExactlyElementsOf(sessionPostIds(oldSession).subList(5, 10));
        assertThat(servedConfigs(oldSession)).containsOnly(SEED_CONFIG);

        // A new session uses the new config: scores never rise down the list, and the top post differs.
        JsonNode after = home("?limit=5", viewer);
        String newSession = after.path("sessionId").asText("");
        assertThat(servedConfigs(newSession)).containsOnly(draft);
        List<Double> scores = new ArrayList<>();
        List<Double> recency = new ArrayList<>();
        sessionItems(newSession).forEach(item -> {
            scores.add(item.path("s").asDouble());
            recency.add(item.path("f").get(7).asDouble());
        });
        assertThat(scores).isSortedAccordingTo((a, b) -> Double.compare(b, a));
        assertThat(recency).isSorted(); // least recent first
        assertThat(postIds(after).getFirst()).isNotEqualTo(postIds(before).getFirst());
    }

    @Test
    void aChangeAnnouncedByAnotherInstanceIsPickedUpThroughListenNotify() {
        UUID draft = configs.createDraft(seedWeights(), params(0.5, 0.0), "MANUAL", "from elsewhere (test)", null);
        assertThat(configs.active().id()).isEqualTo(SEED_CONFIG);

        // What another API instance's activation looks like from here: rows changed, then a NOTIFY.
        tx.executeWithoutResult(status -> {
            dsl.execute("update feed.ranking_config set status = 'ARCHIVED' where id = ?", SEED_CONFIG);
            dsl.execute("update feed.ranking_config set status = 'ACTIVE' where id = ?", draft);
            dsl.fetch("select pg_notify('ranking_config_changed', ?)", draft.toString());
        });

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(configs.active().id()).isEqualTo(draft));
    }

    /** A post deleted after the session was built is left out of its page; nothing else moves. */
    @Test
    void aPostThatGoesAfterTheSessionWasBuiltIsSkippedWhenItsPageIsServed() {
        JsonNode first = home("?limit=5", viewer);
        String sessionId = first.path("sessionId").asText("");
        List<String> stored = sessionPostIds(sessionId);
        // The doomed post is the first of this test's own posts at positions 6 to 9 (position 5 stays).
        // Other tests' posts can rank anywhere (exploration, trending), and only an author can delete a
        // post, so a fixed position used to make this test skip itself when it held someone else's post.
        int doomedAt = -1;
        TestApi.User itsAuthor = null;
        for (int position = 6; position <= 9 && itsAuthor == null; position++) {
            String handle = api.get("/api/v1/posts/" + stored.get(position), viewer)
                    .getBody()
                    .path("author")
                    .path("handle")
                    .asText("");
            itsAuthor = authors.stream()
                    .filter(a -> a.handle().equals(handle))
                    .findFirst()
                    .orElse(null);
            doomedAt = position;
        }
        assertThat(itsAuthor)
                .as("one of positions 6 to 9 is one of this test's posts")
                .isNotNull();
        assertThat(api.delete("/api/v1/posts/" + stored.get(doomedAt), itsAuthor)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode second = home("?limit=5&cursor=" + first.path("nextCursor").asText(""), viewer);

        // Four of the five: positions keep their place in the session, and only what was sent is logged.
        int gone = doomedAt;
        List<Integer> kept = java.util.stream.IntStream.rangeClosed(5, 9)
                .filter(p -> p != gone)
                .boxed()
                .toList();
        assertThat(postIds(second))
                .containsExactlyElementsOf(kept.stream().map(stored::get).toList());
        assertThat(positions(second)).containsExactlyElementsOf(kept);
        assertThat(second.path("nextCursor").asText("")).isEqualTo(sessionId + ":10");
        assertThat(dsl.fetch(
                                "select position from feed.served_item where session_id = ? and position >= 5 order by position",
                                UUID.fromString(sessionId))
                        .getValues(0, Integer.class))
                .containsExactlyElementsOf(kept);
    }

    /** Building a session is expensive: thirty a minute per user, paging through one is not limited. */
    @Test
    void sessionBuildsAreRateLimitedAndPagingIsNot() throws Exception {
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.util.function.Function<String, Integer> get = path -> {
            try {
                return http.send(
                                java.net.http.HttpRequest.newBuilder(java.net.URI.create(rest.getRootUri() + path))
                                        .header("Authorization", "Bearer " + viewer.accessToken())
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.discarding())
                        .statusCode();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        String cursor = home("?limit=2", viewer).path("nextCursor").asText("");

        int built = 1;
        int status = 200;
        for (int i = 0; i < 60 && status == 200; i++) {
            status = get.apply("/api/v1/feed/home?limit=1");
            if (status == 200) {
                built++;
            }
        }

        assertThat(status).isEqualTo(429);
        assertThat(built).isBetween(30, 40);
        // The session in hand can still be paged.
        assertThat(get.apply("/api/v1/feed/home?limit=2&cursor=" + cursor)).isEqualTo(200);
    }

    @Test
    void aMissingExpiredOrForeignSessionIsGoneAndAMalformedCursorIsABadRequest() {
        JsonNode page = home("?limit=5", viewer);
        String cursor = page.path("nextCursor").asText("");
        TestApi.User someoneElse = api.register("nosy");

        assertThat(code(api.get("/api/v1/feed/home?cursor=" + UUID.randomUUID() + ":5", viewer), HttpStatus.GONE))
                .isEqualTo("FEED_SESSION_EXPIRED");
        assertThat(code(api.get("/api/v1/feed/home?cursor=" + cursor, someoneElse), HttpStatus.GONE))
                .isEqualTo("FEED_SESSION_EXPIRED");
        assertThat(code(api.get("/api/v1/feed/home?cursor=not-a-cursor", viewer), HttpStatus.BAD_REQUEST))
                .isEqualTo("CURSOR_INVALID");
        assertThat(code(
                        api.get(
                                "/api/v1/feed/home?cursor="
                                        + page.path("sessionId").asText("") + ":-1",
                                viewer),
                        HttpStatus.BAD_REQUEST))
                .isEqualTo("CURSOR_INVALID");

        // Still fine after 29 minutes, gone after 30.
        clock.advance(Duration.ofMinutes(29));
        assertThat(api.get("/api/v1/feed/home?cursor=" + cursor, viewer).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        clock.advance(Duration.ofMinutes(2));
        assertThat(code(api.get("/api/v1/feed/home?cursor=" + cursor, viewer), HttpStatus.GONE))
                .isEqualTo("FEED_SESSION_EXPIRED");
        // An offset past the end is an empty last page, not an error.
        JsonNode fresh = home("?limit=5", viewer);
        JsonNode beyond = home("?cursor=" + fresh.path("sessionId").asText("") + ":100000", viewer);
        assertThat(beyond.path("items").size()).isZero();
        assertThat(beyond.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void scoreDetailsAreOnlyForAdmins() {
        assertThat(home("?limit=3&debug=true", viewer)
                        .path("items")
                        .get(0)
                        .path("debug")
                        .isNull())
                .isTrue();

        String token = api.post(
                        "/api/v1/auth/login",
                        Map.of("email", "admin@test.local", "password", "admin-test-passphrase"),
                        null)
                .getBody()
                .path("accessToken")
                .asText("");
        TestApi.User admin = new TestApi.User(UUID.randomUUID(), "admin", "admin@test.local", token);

        JsonNode item = home("?limit=3&debug=true", admin).path("items").get(0);

        JsonNode debug = item.path("debug");
        assertThat(debug.path("score").asDouble()).isBetween(0.0, 1.0);
        assertThat(debug.path("contributions").size()).isEqualTo(12);
        double logit = -2.0; // the seeded bias
        for (JsonNode contribution : debug.path("contributions")) {
            assertThat(contribution.path("contribution").asDouble())
                    .isCloseTo(
                            contribution.path("weight").asDouble()
                                    * contribution.path("value").asDouble(),
                            within(1e-9));
            logit += contribution.path("contribution").asDouble();
        }
        assertThat(debug.path("contributions").get(7).path("feature").asText(""))
                .isEqualTo("recency");
        assertThat(debug.path("score").asDouble()).isCloseTo(1 / (1 + Math.exp(-logit)), within(1e-9));
        // Without the flag, an admin gets the plain feed too.
        assertThat(home("?limit=3", admin).path("items").get(0).path("debug").isNull())
                .isTrue();
    }

    @Test
    void aPurgedAccountsServedItemsStayUnderTheTombstoneIdAndItsSessionsGo() {
        String sessionId = home("?limit=4", viewer).path("sessionId").asText("");
        assertThat(api.call(HttpMethod.DELETE, "/api/v1/me", Map.of("password", "correct horse battery"), viewer)
                        .getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        clock.advance(AccountPurgeService.GRACE.plus(Duration.ofHours(1)));
        purge.purgeDue();

        assertThat(dsl.fetch(
                                "select distinct user_id from feed.served_item where session_id = ?",
                                UUID.fromString(sessionId))
                        .getValues(0, UUID.class))
                .containsExactly(new UUID(0L, 0L));
        assertThat(dsl.fetchCount(dsl.selectFrom("feed.feed_session").where("user_id = ?", viewer.id())))
                .isZero();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private JsonNode home(String query, TestApi.User user) {
        ResponseEntity<JsonNode> response = api.get("/api/v1/feed/home" + query, user);
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static String code(ResponseEntity<JsonNode> response, HttpStatus expected) {
        assertThat(response.getStatusCode()).isEqualTo(expected);
        return response.getBody().path("code").asText("");
    }

    private static List<String> postIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("post").path("id").asText("")));
        return ids;
    }

    private static List<Integer> positions(JsonNode page) {
        List<Integer> positions = new ArrayList<>();
        page.path("items").forEach(item -> positions.add(item.path("position").asInt()));
        return positions;
    }

    private JsonNode sessionItems(String sessionId) {
        try {
            return json.readTree(dsl.fetchSingle(
                            "select items::text from feed.feed_session where id = ?", UUID.fromString(sessionId))
                    .get(0, String.class));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> sessionPostIds(String sessionId) {
        List<String> ids = new ArrayList<>();
        sessionItems(sessionId).forEach(item -> ids.add(item.path("p").asText("")));
        return ids;
    }

    private List<UUID> servedConfigs(String sessionId) {
        return dsl.fetch(
                        "select distinct config_id from feed.served_item where session_id = ?",
                        UUID.fromString(sessionId))
                .getValues(0, UUID.class);
    }

    private static Map<String, Double> seedWeights() {
        Map<String, Double> weights = new HashMap<>();
        weights.put("bias", -2.0);
        weights.put("follows_author", 1.6);
        weights.put("follows_artist", 1.2);
        weights.put("artist_affinity", 1.0);
        weights.put("author_affinity", 0.8);
        weights.put("genre_similarity", 1.0);
        weights.put("gig_relation", 1.4);
        weights.put("proximity", 0.6);
        weights.put("recency", 1.5);
        weights.put("engagement_quality", 1.8);
        weights.put("social_proof", 0.7);
        weights.put("seen", -2.5);
        weights.put("position_prior", 0.0);
        return weights;
    }

    private static RankingConfig.Params params(double lambda, double exploration) {
        return new RankingConfig.Params(
                24,
                20,
                lambda,
                exploration,
                200,
                10,
                Map.of(
                        "FOLLOWING",
                        300,
                        "ARTIST",
                        300,
                        "GIG",
                        200,
                        "TASTE",
                        300,
                        "LOCAL",
                        200,
                        "TRENDING",
                        200,
                        "FRESH",
                        100),
                50,
                3,
                5);
    }
}
