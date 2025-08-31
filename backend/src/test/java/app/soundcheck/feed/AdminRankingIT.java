package app.soundcheck.feed;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.feed.internal.RankingConfigs;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The console's ranking API. Configs are listed, saved as drafts (validated), edited only while
 * drafts, activated and rolled back; a new feed session uses what was activated. The preview ranks a
 * user's feed under two configs without writing anything, and in the live feed's order.
 */
class AdminRankingIT extends IntegrationTest {

    static final UUID SEED_CONFIG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    static final String CONFIGS = "/api/v1/admin/ranking/configs";

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
    JsonMapper json;

    TestApi api;
    TestData data;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
    }

    @AfterEach
    void restore() {
        // Other tests expect the seeded config to be the only one, and ACTIVE.
        dsl.execute(
                "update feed.ranking_config set status = 'ARCHIVED' where status = 'ACTIVE' and id <> ?", SEED_CONFIG);
        dsl.execute("update feed.ranking_config set status = 'ACTIVE' where id = ?", SEED_CONFIG);
        dsl.execute("delete from feed.ranking_config where id <> ?", SEED_CONFIG);
        configs.reload();
    }

    // ── Configs ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void aDraftIsSavedListedNewestFirstAndReadBack() {
        JsonNode draft = created(api.post(CONFIGS, body(seedWeights(), seedParams(), "first try"), api.admin()));

        assertThat(draft.path("status").asText("")).isEqualTo("DRAFT");
        assertThat(draft.path("source").asText("")).isEqualTo("MANUAL");
        assertThat(draft.path("version").asInt()).isGreaterThan(1);
        assertThat(draft.path("createdBy").asText(""))
                .isEqualTo(api.admin().id().toString());
        assertThat(draft.path("createdByHandle").asText(""))
                .isEqualTo(api.admin().handle());
        assertThat(draft.path("params").path("authorCapPerSession").asInt()).isEqualTo(5);

        JsonNode list = ok(api.get(CONFIGS, api.admin()));
        assertThat(list.get(0).path("id").asText("")).isEqualTo(draft.path("id").asText(""));
        assertThat(list.get(list.size() - 1).path("id").asText("")).isEqualTo(SEED_CONFIG.toString());
        assertThat(list.get(list.size() - 1).path("status").asText("")).isEqualTo("ACTIVE");
        assertThat(list.get(list.size() - 1).path("source").asText("")).isEqualTo("SEED");

        JsonNode one = ok(api.get(CONFIGS + "/" + draft.path("id").asText(""), api.admin()));
        assertThat(one.path("notes").asText("")).isEqualTo("first try");
        assertThat(one.path("weights").path("recency").asDouble()).isEqualTo(1.5);
    }

    @Test
    void aLabDraftKeepsItsMetrics() {
        Map<String, Object> body = body(seedWeights(), seedParams(), "trained");
        body.put("source", "LAB");
        body.put("metrics", Map.of("auc", 0.71, "report", "lab/reports/run-1.html"));

        JsonNode draft = created(api.post(CONFIGS, body, api.admin()));

        assertThat(draft.path("source").asText("")).isEqualTo("LAB");
        assertThat(draft.path("metrics").path("auc").asDouble()).isEqualTo(0.71);
        assertThat(draft.path("metrics").path("report").asText("")).isEqualTo("lab/reports/run-1.html");
    }

    @Test
    void anInvalidConfigIsRefusedWithEveryBrokenRule() {
        Map<String, Double> weights = seedWeights();
        weights.put("recency", 11.0);
        weights.remove("seen");
        Map<String, Object> params = seedParams();
        params.put("diversityLambda", 1.5);
        params.put("authorCapPerSession", 0);

        ResponseEntity<JsonNode> response = api.post(CONFIGS, body(weights, params, null), api.admin());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("code").asText("")).isEqualTo("RANKING_CONFIG_INVALID");
        List<String> messages = new ArrayList<>();
        response.getBody()
                .path("errors")
                .forEach(e -> messages.add(e.path("message").asText("")));
        assertThat(messages)
                .anyMatch(m -> m.contains("missing [seen]"))
                .anyMatch(m -> m.startsWith("weight recency"))
                .anyMatch(m -> m.startsWith("diversityLambda"))
                .anyMatch(m -> m.startsWith("authorCapPerSession"));
    }

    @Test
    void aDraftCanBeEditedButAnActiveOrArchivedConfigCannot() {
        String id = created(api.post(CONFIGS, body(seedWeights(), seedParams(), null), api.admin()))
                .path("id")
                .asText("");
        Map<String, Double> weights = seedWeights();
        weights.put("recency", 0.0);

        JsonNode edited =
                ok(api.patch(CONFIGS + "/" + id, Map.of("weights", weights, "notes", "no recency"), api.admin()));
        assertThat(edited.path("weights").path("recency").asDouble()).isZero();
        assertThat(edited.path("notes").asText("")).isEqualTo("no recency");
        assertThat(edited.path("params").path("halfLifeHours").asDouble()).isEqualTo(24); // left as it was

        // The same checks as a new draft.
        Map<String, Double> broken = seedWeights();
        broken.put("seen", Double.MAX_VALUE);
        assertThat(api.patch(CONFIGS + "/" + id, Map.of("weights", broken), api.admin())
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(code(api.patch(CONFIGS + "/" + SEED_CONFIG, Map.of("notes", "x"), api.admin()), HttpStatus.CONFLICT))
                .isEqualTo("CONFIG_NOT_DRAFT");
        ok(api.post(CONFIGS + "/" + id + "/activate", null, api.admin()));
        // The seed is ARCHIVED now: still not editable; nor is the one just activated.
        assertThat(code(api.patch(CONFIGS + "/" + SEED_CONFIG, Map.of("notes", "x"), api.admin()), HttpStatus.CONFLICT))
                .isEqualTo("CONFIG_NOT_DRAFT");
        assertThat(code(api.patch(CONFIGS + "/" + id, Map.of("notes", "x"), api.admin()), HttpStatus.CONFLICT))
                .isEqualTo("CONFIG_NOT_DRAFT");
        assertThat(code(
                        api.patch(CONFIGS + "/" + UUID.randomUUID(), Map.of("notes", "x"), api.admin()),
                        HttpStatus.NOT_FOUND))
                .isEqualTo("RANKING_CONFIG_NOT_FOUND");
    }

    @Test
    void activatingADraftRecordsWhoAndANewSessionUsesItAndTheArchivedOneCanBeRestored() {
        TestApi.User viewer = api.register("cfgviewer");
        String id = created(api.post(CONFIGS, body(seedWeights(), seedParams(), "next"), api.admin()))
                .path("id")
                .asText("");

        JsonNode active = ok(api.post(CONFIGS + "/" + id + "/activate", null, api.admin()));

        assertThat(active.path("status").asText("")).isEqualTo("ACTIVE");
        assertThat(active.path("activatedBy").asText(""))
                .isEqualTo(api.admin().id().toString());
        assertThat(active.path("activatedByHandle").asText(""))
                .isEqualTo(api.admin().handle());
        assertThat(active.path("activatedAt").asText("")).isNotBlank();
        assertThat(ok(api.get(CONFIGS + "/" + SEED_CONFIG, api.admin()))
                        .path("status")
                        .asText(""))
                .isEqualTo("ARCHIVED");
        assertThat(sessionConfig(viewer)).isEqualTo(UUID.fromString(id));
        assertThat(code(api.post(CONFIGS + "/" + id + "/activate", null, api.admin()), HttpStatus.CONFLICT))
                .isEqualTo("CONFIG_ALREADY_ACTIVE");

        // "Re-activate previous": the archived seed goes live again.
        JsonNode restored = ok(api.post(CONFIGS + "/" + SEED_CONFIG + "/activate", null, api.admin()));

        assertThat(restored.path("status").asText("")).isEqualTo("ACTIVE");
        assertThat(ok(api.get(CONFIGS + "/" + id, api.admin())).path("status").asText(""))
                .isEqualTo("ARCHIVED");
        assertThat(sessionConfig(viewer)).isEqualTo(SEED_CONFIG);
    }

    @Test
    void aUserCannotTouchConfigs() {
        TestApi.User user = api.register("cfgnosy");
        assertThat(api.get(CONFIGS, user).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.post(CONFIGS + "/" + SEED_CONFIG + "/activate", null, user)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Preview ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void previewWritesNothingAndTwoEqualConfigsAgreeCompletely() {
        Fixture f = fixture();
        long sessionsBefore = dsl.fetchCount(dsl.selectFrom("feed.feed_session").where("user_id = ?", f.viewer.id()));
        long servedBefore = dsl.fetchCount(dsl.selectFrom("feed.served_item").where("user_id = ?", f.viewer.id()));

        JsonNode preview = ok(api.post(
                "/api/v1/admin/ranking/preview",
                Map.of(
                        "userId", f.viewer.id(),
                        "configA", Map.of("id", SEED_CONFIG),
                        "configB", Map.of("weights", seedWeights(), "params", seedParams()),
                        "limit", 20),
                api.admin()));

        assertThat(dsl.fetchCount(dsl.selectFrom("feed.feed_session").where("user_id = ?", f.viewer.id())))
                .isEqualTo(sessionsBefore);
        assertThat(dsl.fetchCount(dsl.selectFrom("feed.served_item").where("user_id = ?", f.viewer.id())))
                .isEqualTo(servedBefore);
        assertThat(preview.path("a").path("items").size()).isEqualTo(20);
        assertThat(ids(preview.path("a"))).containsExactlyElementsOf(ids(preview.path("b")));
        assertThat(preview.path("overlapAt10").asDouble()).isEqualTo(1.0);
        assertThat(preview.path("kendallTau").asDouble()).isEqualTo(1.0);
        assertThat(preview.path("commonItems").asInt()).isEqualTo(20);
        assertThat(preview.path("seed").isNull()).isTrue();
        assertThat(preview.path("a").path("configId").asText("")).isEqualTo(SEED_CONFIG.toString());
        assertThat(preview.path("b").path("configId").isNull()).isTrue();

        JsonNode top = preview.path("a").path("items").get(0);
        assertThat(top.path("rank").asInt()).isEqualTo(1);
        assertThat(top.path("otherRank").asInt()).isEqualTo(1);
        assertThat(top.path("post").path("authorHandle").asText("")).isNotBlank();
        assertThat(top.path("contributions").size()).isEqualTo(12);
        // The waterfall adds up: bias + Σ contributions = logit, σ(logit) = score.
        double logit = top.path("bias").asDouble();
        for (JsonNode c : top.path("contributions")) {
            logit += c.path("contribution").asDouble();
        }
        assertThat(logit).isCloseTo(top.path("logit").asDouble(), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(1 / (1 + Math.exp(-logit)))
                .isCloseTo(top.path("score").asDouble(), org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void settingRecencyToZeroReordersThePreview() {
        Fixture f = fixture();
        Map<String, Double> noRecency = seedWeights();
        noRecency.put("recency", 0.0);

        JsonNode preview = ok(api.post(
                "/api/v1/admin/ranking/preview",
                Map.of(
                        "userId", f.viewer.id(),
                        "configA", Map.of("id", SEED_CONFIG),
                        "configB", Map.of("weights", noRecency, "params", seedParams())),
                api.admin()));

        assertThat(preview.path("kendallTau").asDouble()).isLessThan(1.0);
        assertThat(ids(preview.path("a"))).isNotEqualTo(ids(preview.path("b")));
    }

    @Test
    void aChangedHalfLifeRecomputesRecencyFromTheCachedFacts() {
        Fixture f = fixture();
        Map<String, Object> shortHalfLife = seedParams();
        shortHalfLife.put("halfLifeHours", 1);

        JsonNode preview = ok(api.post(
                "/api/v1/admin/ranking/preview",
                Map.of(
                        "userId", f.viewer.id(),
                        "configA", Map.of("id", SEED_CONFIG),
                        "configB", Map.of("weights", seedWeights(), "params", shortHalfLife),
                        "limit", 50),
                api.admin()));

        double recencyA = feature(preview.path("a").path("items").get(0), "recency");
        JsonNode sameInB = find(
                preview.path("b"),
                preview.path("a").path("items").get(0).path("post").path("id"));
        assertThat(feature(sameInB, "recency")).isLessThan(recencyA);
    }

    /**
     * The preview is the live feed's ranking. With exploration at 0 (so neither side draws
     * random numbers), a session built right after a preview has the preview's order.
     */
    @Test
    void thePreviewHasTheOrderTheLiveFeedServes() throws Exception {
        Fixture f = fixture();
        Map<String, Object> noExploration = seedParams();
        noExploration.put("explorationRate", 0);
        String id = created(api.post(CONFIGS, body(seedWeights(), noExploration, "parity"), api.admin()))
                .path("id")
                .asText("");
        ok(api.post(CONFIGS + "/" + id + "/activate", null, api.admin()));

        JsonNode preview = ok(api.post(
                "/api/v1/admin/ranking/preview",
                Map.of("userId", f.viewer.id(), "configA", Map.of("id", id), "limit", 50),
                api.admin()));
        JsonNode home = ok(api.get("/api/v1/feed/home?limit=10", f.viewer));

        List<String> session = new ArrayList<>();
        json.readTree(dsl.fetchSingle(
                                "select items::text from feed.feed_session where id = ?",
                                UUID.fromString(home.path("sessionId").asText("")))
                        .get(0, String.class))
                .forEach(item -> session.add(item.path("p").asText("")));
        List<String> previewed = ids(preview.path("a"));
        assertThat(previewed).hasSize(Math.min(50, session.size()));
        assertThat(session.subList(0, previewed.size())).containsExactlyElementsOf(previewed);
        assertThat(preview.path("b").isNull()).isTrue();
    }

    @Test
    void previewNamesAMissingUserOrConfig() {
        assertThat(code(
                        api.post(
                                "/api/v1/admin/ranking/preview",
                                Map.of("userId", UUID.randomUUID(), "configA", Map.of("id", SEED_CONFIG)),
                                api.admin()),
                        HttpStatus.NOT_FOUND))
                .isEqualTo("USER_NOT_FOUND");
        TestApi.User someone = api.register("pvmissing");
        assertThat(code(
                        api.post(
                                "/api/v1/admin/ranking/preview",
                                Map.of("userId", someone.id(), "configA", Map.of("id", UUID.randomUUID())),
                                api.admin()),
                        HttpStatus.NOT_FOUND))
                .isEqualTo("RANKING_CONFIG_NOT_FOUND");
        assertThat(code(
                        api.post(
                                "/api/v1/admin/ranking/preview",
                                Map.of("userId", someone.id(), "configA", Map.of("weights", seedWeights())),
                                api.admin()),
                        HttpStatus.BAD_REQUEST))
                .isEqualTo("CONFIG_REF_INVALID");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    /**
     * A viewer following six authors with thirty posts spread over two days (as HomeFeedIT does). The
     * first post created is the oldest: ids (UUIDv7) break ties between equal scores, so with recency out
     * of the score the order really changes.
     */
    record Fixture(TestApi.User viewer, List<UUID> posts) {}

    Fixture fixture() {
        TestApi.User viewer = api.register("pvviewer");
        List<TestApi.User> authors = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            TestApi.User author = api.register("pvauthor");
            api.put("/api/v1/users/" + author.id() + "/follow", null, viewer);
            authors.add(author);
        }
        List<UUID> posts = new ArrayList<>();
        Instant now = Instant.now();
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
                    now.minus(Duration.ofMinutes(90L * (29 - i))).toString(),
                    post);
            posts.add(post);
        }
        return new Fixture(viewer, posts);
    }

    private UUID sessionConfig(TestApi.User viewer) {
        JsonNode home = ok(api.get("/api/v1/feed/home?limit=1", viewer));
        return dsl.fetchSingle(
                        "select config_id from feed.feed_session where id = ?",
                        UUID.fromString(home.path("sessionId").asText("")))
                .get(0, UUID.class);
    }

    static Map<String, Object> body(Map<String, Double> weights, Map<String, Object> params, String notes) {
        Map<String, Object> body = new HashMap<>();
        body.put("weights", weights);
        body.put("params", params);
        if (notes != null) {
            body.put("notes", notes);
        }
        return body;
    }

    static Map<String, Double> seedWeights() {
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

    static Map<String, Object> seedParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("halfLifeHours", 24);
        params.put("priorStrength", 20);
        params.put("diversityLambda", 0.8);
        params.put("explorationRate", 0.1);
        params.put("sessionSize", 200);
        params.put("pageSize", 10);
        params.put(
                "candidateCaps",
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
                        100));
        params.put("localRadiusKm", 50);
        params.put("seenWindowDays", 3);
        params.put("authorCapPerSession", 5);
        return params;
    }

    private static List<String> ids(JsonNode list) {
        List<String> ids = new ArrayList<>();
        list.path("items").forEach(item -> ids.add(item.path("post").path("id").asText("")));
        return ids;
    }

    private static JsonNode find(JsonNode list, JsonNode postId) {
        for (JsonNode item : list.path("items")) {
            if (item.path("post").path("id").equals(postId)) {
                return item;
            }
        }
        throw new AssertionError("post " + postId + " is not in the list");
    }

    private static double feature(JsonNode item, String name) {
        for (JsonNode c : item.path("contributions")) {
            if (c.path("feature").asText("").equals(name)) {
                return c.path("value").asDouble();
            }
        }
        throw new AssertionError("no feature " + name);
    }

    private static JsonNode created(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static String code(ResponseEntity<JsonNode> response, HttpStatus expected) {
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(expected);
        return response.getBody().path("code").asText("");
    }
}
