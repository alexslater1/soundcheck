package app.soundcheck.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.soundcheck.shared.Ids;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Replay re-scores the served items of a window from their logged features and compares the
 * scores with the outcomes ({@code feed.served_outcome}). The rows are written by hand in an hour two to
 * five days ahead (its partitions exist; nothing else writes there), with only {@code recency} set, so each
 * config's score order is the recency order and every number can be worked out on paper.
 */
class RankingReplayIT extends IntegrationTest {

    static final UUID SEED_CONFIG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    static final int RECENCY = 7;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DSLContext dsl;

    TestApi api;
    Instant from;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        // A fresh hour per test, two to five days ahead (partitions exist seven days ahead), so tests don't see each
        // other's rows.
        from = Instant.now()
                .plus(Duration.ofDays(2))
                .truncatedTo(ChronoUnit.HOURS)
                .plus(Duration.ofMinutes(ThreadLocalRandom.current().nextInt(0, 3 * 24 * 60)));
    }

    /**
     * Seven items in three sessions; recency (so the score) and outcome:
     *
     * <pre>
     *   S1: 0.9 –, 0.5 –, 0.1 +
     *   S2: 0.2 –, 0.8 +
     *   S3 (exploration): 0.7 +, 0.3 –
     * </pre>
     *
     * AUC: positives 0.1, 0.8, 0.7 against negatives 0.9, 0.5, 0.2, 0.3 win 0 + 3 + 3 of 12 pairs = 0.5.
     * NDCG@10: S1's positive comes third (1 / log₂4 = 0.5), S2's and S3's first (1): mean 2.5 / 3.
     */
    @Test
    void theMetricsAreThoseWorkedOutByHand() {
        seed();

        JsonNode result = replay(Map.of("id", SEED_CONFIG), from, from.plus(Duration.ofHours(1)), false);

        assertThat(result.path("rows").asLong()).isEqualTo(7);
        assertThat(result.path("sessions").asLong()).isEqualTo(3);
        assertThat(result.path("positives").asLong()).isEqualTo(3);
        assertThat(result.path("sessionsWithPositive").asLong()).isEqualTo(3);
        assertThat(result.path("auc").asDouble()).isCloseTo(0.5, within(1e-9));
        assertThat(result.path("ndcgAt10").asDouble()).isCloseTo(2.5 / 3, within(1e-9));
        assertThat(result.path("logLoss").asDouble()).isCloseTo(expectedLogLoss(), within(1e-6));
        assertThat(result.path("sampled").asBoolean()).isFalse();
        assertThat(result.path("sampleFraction").asDouble()).isEqualTo(1.0);
        assertThat(result.path("configId").asText("")).isEqualTo(SEED_CONFIG.toString());
        // Replayed position 1 holds 0.9 –, 0.8 +, 0.7 +; position 2 0.5 –, 0.2 –, 0.3 –; position 3 0.1 +.
        JsonNode byPosition = result.path("byPosition");
        assertThat(byPosition.size()).isEqualTo(3);
        assertThat(byPosition.get(0).path("items").asLong()).isEqualTo(3);
        assertThat(byPosition.get(0).path("positives").asLong()).isEqualTo(2);
        assertThat(byPosition.get(1).path("positives").asLong()).isZero();
        assertThat(byPosition.get(2).path("rate").asDouble()).isEqualTo(1.0);
    }

    @Test
    void anotherConfigIsJudgedOnTheSameRows() {
        seed();
        // Recency weighted negatively turns each session over: S1's positive comes first, S2's and S3's
        // second of two.
        Map<String, Double> weights = AdminRankingIT.seedWeights();
        weights.put("recency", -1.5);

        JsonNode result = replay(
                Map.of("weights", weights, "params", AdminRankingIT.seedParams()),
                from,
                from.plus(Duration.ofHours(1)),
                false);

        assertThat(result.path("configId").isNull()).isTrue();
        double secondOfTwo = 1 / (Math.log(3) / Math.log(2));
        assertThat(result.path("ndcgAt10").asDouble()).isCloseTo((1 + secondOfTwo + secondOfTwo) / 3, within(1e-9));
    }

    @Test
    void explorationOnlyKeepsTheRandomSlots() {
        seed();

        JsonNode result = replay(Map.of("id", SEED_CONFIG), from, from.plus(Duration.ofHours(1)), true);

        assertThat(result.path("rows").asLong()).isEqualTo(2);
        assertThat(result.path("sessions").asLong()).isEqualTo(1);
        assertThat(result.path("auc").asDouble()).isEqualTo(1.0);
        assertThat(result.path("explorationOnly").asBoolean()).isTrue();
    }

    @Test
    void anEmptyWindowHasNoMetrics() {
        JsonNode result = replay(Map.of("id", SEED_CONFIG), from, from.plus(Duration.ofMinutes(1)), false);

        assertThat(result.path("rows").asLong()).isZero();
        assertThat(result.path("auc").isNull()).isTrue();
        assertThat(result.path("logLoss").isNull()).isTrue();
        assertThat(result.path("ndcgAt10").isNull()).isTrue();
        assertThat(result.path("byPosition").size()).isZero();
    }

    @Test
    void itemsLoggedWithAnotherFeatureSchemaAreRefused() {
        UUID session = Ids.newId();
        dsl.execute(
                "insert into feed.served_item (session_id, position, served_at, user_id, post_id, config_id, score,"
                        + " features, exploration) values (?, 0, ?::timestamptz, ?, ?, ?, 0.5, '{0.1,0.2}'::real[],"
                        + " false)",
                session,
                from.plusSeconds(1).toString(),
                Ids.newId(),
                Ids.newId(),
                SEED_CONFIG);

        ResponseEntity<JsonNode> response = api.post(
                "/api/v1/admin/ranking/replay",
                Map.of(
                        "config",
                        Map.of("id", SEED_CONFIG),
                        "from",
                        from.toString(),
                        "to",
                        from.plus(Duration.ofHours(1)).toString()),
                api.admin());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().path("code").asText("")).isEqualTo("FEATURE_SCHEMA_MISMATCH");
    }

    @Test
    void theWindowIsAtMostFourteenDaysAndRunsForwards() {
        assertThat(code(
                        Map.of("id", SEED_CONFIG),
                        from,
                        from.plus(Duration.ofDays(14).plusSeconds(1))))
                .isEqualTo("REPLAY_WINDOW_INVALID");
        assertThat(code(Map.of("id", SEED_CONFIG), from, from)).isEqualTo("REPLAY_WINDOW_INVALID");
        TestApi.User user = api.register("replaynosy");
        assertThat(api.post("/api/v1/admin/ranking/replay", Map.of(), user).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private void seed() {
        UUID user = Ids.newId();
        UUID s1 = Ids.newId();
        UUID s2 = Ids.newId();
        UUID s3 = Ids.newId();
        serve(s1, user, 0, 0.9, false, null);
        serve(s1, user, 1, 0.5, false, null);
        serve(s1, user, 2, 0.1, false, "LIKE");
        serve(s2, user, 0, 0.2, false, null);
        serve(s2, user, 1, 0.8, false, "COMPLETE");
        serve(s3, user, 3, 0.7, true, "SHARE");
        serve(s3, user, 4, 0.3, true, "IMPRESSION");
    }

    private void serve(UUID session, UUID user, int position, double recency, boolean exploration, String event) {
        UUID post = Ids.newId();
        Instant at = from.plusSeconds(10 + position);
        List<String> features = new ArrayList<>(Collections.nCopies(12, "0"));
        features.set(RECENCY, String.valueOf(recency));
        dsl.execute(
                "insert into feed.served_item (session_id, position, served_at, user_id, post_id, config_id, score,"
                        + " features, exploration) values (?, ?, ?::timestamptz, ?, ?, ?, 0.5, ?::real[], ?)",
                session,
                position,
                at.toString(),
                user,
                post,
                SEED_CONFIG,
                "{" + String.join(",", features) + "}",
                exploration);
        if (event != null) {
            dsl.execute(
                    "insert into signals.interaction_event (occurred_at, user_id, post_id, feed_session_id, surface,"
                            + " position, event_type, value) values (?::timestamptz, ?, ?, ?, 'FOR_YOU', ?, ?, null)",
                    at.plusSeconds(5).toString(),
                    user,
                    post,
                    session,
                    position,
                    event);
        }
    }

    /** −mean(y·ln p + (1 − y)·ln(1 − p)) with p = σ(−2 + 1.5·recency), the seed's bias and recency weight. */
    private static double expectedLogLoss() {
        double[][] rows = {{0.9, 0}, {0.5, 0}, {0.1, 1}, {0.2, 0}, {0.8, 1}, {0.7, 1}, {0.3, 0}};
        double sum = 0;
        for (double[] row : rows) {
            // The features are stored as real (float), so the replay sees the float value.
            double p = 1 / (1 + Math.exp(-(-2 + 1.5 * (float) row[0])));
            sum -= row[1] > 0 ? Math.log(p) : Math.log(1 - p);
        }
        return sum / rows.length;
    }

    private JsonNode replay(Map<String, Object> config, Instant from, Instant to, boolean explorationOnly) {
        ResponseEntity<JsonNode> response = api.post(
                "/api/v1/admin/ranking/replay",
                Map.of(
                        "config",
                        config,
                        "from",
                        from.toString(),
                        "to",
                        to.toString(),
                        "explorationOnly",
                        explorationOnly),
                api.admin());
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private String code(Map<String, Object> config, Instant from, Instant to) {
        ResponseEntity<JsonNode> response = api.post(
                "/api/v1/admin/ranking/replay",
                Map.of("config", config, "from", from.toString(), "to", to.toString()),
                api.admin());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        return response.getBody().path("code").asText("");
    }
}
