package app.soundcheck.feed.internal;

import static app.soundcheck.shared.jooq.feed.Tables.RANKING_CONFIG;
import static app.soundcheck.shared.jooq.feed.Tables.SERVED_ITEM;
import static app.soundcheck.shared.jooq.feed.Tables.SERVED_OUTCOME;

import app.soundcheck.feed.internal.ranking.FeatureSchema;
import app.soundcheck.feed.internal.ranking.FeatureVector;
import app.soundcheck.feed.internal.ranking.RankingConfig;
import app.soundcheck.feed.internal.ranking.RankingMetrics;
import app.soundcheck.feed.internal.ranking.Scorer;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.Cursor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Record4;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replay evaluation: how well a config would have ranked what was actually served. Each
 * served item in the window is re-scored from the features it was logged with, then compared with what
 * the user did with it ({@code feed.served_outcome}, the one label).
 *
 * <p>Biased by construction: the log only holds what the live config chose to show, in the order it
 * showed it. A config that would have shown other posts is judged only on the posts it would have shared
 * with the live one. The exploration-only option keeps the slots exploration filled at random, which are
 * a fairer (and much smaller) sample.
 */
@Service
@ConditionalOnRole(Roles.API)
public class RankingReplay {

    static final Duration MAX_WINDOW = Duration.ofDays(14);
    static final long MAX_ROWS = 200_000;
    static final int NDCG_K = 10;
    /** Engagement is reported for the first this-many replayed positions of a session. */
    static final int POSITIONS = 20;
    /** Sampling keeps sessions whose hash falls in the first {@code buckets} of this many. */
    static final int SAMPLE_BUCKETS = 10_000;

    /**
     * The result.
     *
     * @param rows                  served items replayed
     * @param sessions              feed sessions they belong to
     * @param sessionsWithPositive  sessions NDCG was averaged over
     * @param auc                   null without both a positive and a negative
     * @param ndcgAt10              the mean over sessions with at least one positive; null when there are none
     * @param byPosition            engagement by position in the replayed order of each session
     * @param sampled               true when the window held more than {@value #MAX_ROWS} rows and only a
     *                              deterministic share of its sessions was replayed
     * @param sampleFraction        the share of sessions kept (1 when not sampled)
     */
    @Schema(name = "RankingReplayResult")
    public record Result(
            @Schema(nullable = true) UUID configId,
            int configVersion,
            Instant from,
            Instant to,
            boolean explorationOnly,
            long rows,
            long sessions,
            long positives,
            long sessionsWithPositive,
            @Schema(nullable = true) Double auc,
            @Schema(nullable = true) Double logLoss,
            @Schema(nullable = true) Double ndcgAt10,
            List<PositionEngagement> byPosition,
            boolean sampled,
            double sampleFraction) {}

    /** Of the items the replayed config put at this position (1 = top) of their session, how many were positive. */
    @Schema(name = "ReplayPositionEngagement")
    public record PositionEngagement(int position, long items, long positives, @Schema(nullable = true) Double rate) {}

    private final DSLContext dsl;
    private final FeatureSchema schema;

    RankingReplay(DSLContext dsl, FeatureSchema schema) {
        this.dsl = dsl;
        this.schema = schema;
    }

    /**
     * @throws ApiException 400 {@code REPLAY_WINDOW_INVALID} for an empty or reversed window or one over
     *     14 days; 422 {@code FEATURE_SCHEMA_MISMATCH} when the window holds items logged with another
     *     feature schema than the config's
     */
    @Transactional(readOnly = true)
    public Result replay(RankingConfig config, Instant from, Instant to, boolean explorationOnly) {
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw ApiException.badRequest(
                    "REPLAY_WINDOW_INVALID", "The window must start before it ends and be at most 14 days long");
        }
        Condition window = SERVED_ITEM
                .SERVED_AT
                .ge(from.atOffset(ZoneOffset.UTC))
                .and(SERVED_ITEM.SERVED_AT.lt(to.atOffset(ZoneOffset.UTC)))
                .and(explorationOnly ? SERVED_ITEM.EXPLORATION.isTrue() : DSL.noCondition());

        // One pass over served_item (its partition key prunes the window) for the size and the schema check.
        Record size = dsl.select(
                        DSL.count().as("n_rows"),
                        DSL.boolOr(RANKING_CONFIG
                                        .FEATURE_SCHEMA_VERSION
                                        .ne(config.featureSchemaVersion())
                                        .or(DSL.field("cardinality({0})", Integer.class, SERVED_ITEM.FEATURES)
                                                .ne(schema.size())))
                                .as("mismatch"))
                .from(SERVED_ITEM)
                .join(RANKING_CONFIG)
                .on(RANKING_CONFIG.ID.eq(SERVED_ITEM.CONFIG_ID))
                .where(window)
                .fetchSingle();
        long total = size.get("n_rows", Long.class);
        if (Boolean.TRUE.equals(size.get("mismatch", Boolean.class))) {
            throw ApiException.unprocessable(
                    "FEATURE_SCHEMA_MISMATCH",
                    "The window holds items logged with another feature schema than version "
                            + config.featureSchemaVersion() + "; replay a shorter or later window");
        }
        double fraction = total > MAX_ROWS ? (double) MAX_ROWS / total : 1;
        int buckets = Math.max(1, (int) Math.floor(fraction * SAMPLE_BUCKETS));

        Condition outcomes = SERVED_OUTCOME
                .SERVED_AT
                .ge(from.atOffset(ZoneOffset.UTC))
                .and(SERVED_OUTCOME.SERVED_AT.lt(to.atOffset(ZoneOffset.UTC)))
                .and(explorationOnly ? SERVED_OUTCOME.EXPLORATION.isTrue() : DSL.noCondition())
                // A deterministic sample of whole sessions: the same window always keeps the same ones.
                .and(
                        fraction < 1
                                ? DSL.condition(
                                        "mod(abs(hashtext({0}::text)), {1}) < {2}",
                                        SERVED_OUTCOME.SESSION_ID, DSL.inline(SAMPLE_BUCKETS), DSL.val(buckets))
                                : DSL.noCondition());

        Accumulator acc = new Accumulator(config, Math.min(total, MAX_ROWS + MAX_ROWS / 10));
        // Read as a stream (a server-side cursor inside this read-only transaction), session by session.
        try (Cursor<Record4<UUID, Integer, Boolean, Float[]>> rows = dsl.select(
                        SERVED_OUTCOME.SESSION_ID,
                        SERVED_OUTCOME.POSITION,
                        SERVED_OUTCOME.POSITIVE,
                        SERVED_OUTCOME.FEATURES)
                .from(SERVED_OUTCOME)
                .where(outcomes)
                .orderBy(SERVED_OUTCOME.SESSION_ID, SERVED_OUTCOME.POSITION)
                .fetchSize(5_000)
                .fetchLazy()) {
            for (Record4<UUID, Integer, Boolean, Float[]> row : rows) {
                acc.add(row.value1(), row.value2(), Boolean.TRUE.equals(row.value3()), row.value4());
            }
        }
        return acc.result(from, to, explorationOnly, fraction < 1 ? (double) buckets / SAMPLE_BUCKETS : 1);
    }

    /** Collects the scores and labels, and the per-session numbers at each session's end. */
    private static final class Accumulator {

        private final RankingConfig config;
        private double[] scores;
        private boolean[] labels;
        private int n;
        private long sessions;
        private long positives;
        private long sessionsWithPositive;
        private double ndcgSum;
        private final long[] itemsAt = new long[POSITIONS];
        private final long[] positivesAt = new long[POSITIONS];

        private @Nullable UUID session;
        private final List<double[]> sessionItems = new ArrayList<>(); // {score, label}

        Accumulator(RankingConfig config, long expected) {
            this.config = config;
            int capacity = (int) Math.max(16, expected);
            this.scores = new double[capacity];
            this.labels = new boolean[capacity];
        }

        void add(UUID sessionId, int position, boolean positive, Float[] logged) {
            if (!sessionId.equals(session)) {
                closeSession();
                session = sessionId;
                sessions++;
            }
            double[] features = new double[logged.length];
            for (int i = 0; i < logged.length; i++) {
                features[i] = logged[i] == null ? 0 : logged[i];
            }
            double score = Scorer.score(config, FeatureVector.of(features)).score();
            if (n == scores.length) {
                scores = Arrays.copyOf(scores, n * 2);
                labels = Arrays.copyOf(labels, n * 2);
            }
            scores[n] = score;
            labels[n] = positive;
            n++;
            if (positive) {
                positives++;
            }
            // Rows arrive in logged order, so a stable sort later keeps that order among equal scores.
            sessionItems.add(new double[] {score, positive ? 1 : 0});
        }

        private void closeSession() {
            if (sessionItems.isEmpty()) {
                return;
            }
            double[] s = new double[sessionItems.size()];
            boolean[] l = new boolean[sessionItems.size()];
            for (int i = 0; i < s.length; i++) {
                s[i] = sessionItems.get(i)[0];
                l[i] = sessionItems.get(i)[1] > 0;
            }
            Double ndcg = RankingMetrics.ndcgAt(NDCG_K, s, l);
            if (ndcg != null) {
                ndcgSum += ndcg;
                sessionsWithPositive++;
            }
            List<double[]> replayed = new ArrayList<>(sessionItems);
            replayed.sort(Comparator.comparingDouble((double[] item) -> item[0]).reversed());
            for (int r = 0; r < Math.min(POSITIONS, replayed.size()); r++) {
                itemsAt[r]++;
                if (replayed.get(r)[1] > 0) {
                    positivesAt[r]++;
                }
            }
            sessionItems.clear();
        }

        Result result(Instant from, Instant to, boolean explorationOnly, double sampleFraction) {
            closeSession();
            double[] s = Arrays.copyOf(scores, n);
            boolean[] l = Arrays.copyOf(labels, n);
            List<PositionEngagement> byPosition = new ArrayList<>();
            for (int r = 0; r < POSITIONS && itemsAt[r] > 0; r++) {
                byPosition.add(new PositionEngagement(
                        r + 1, itemsAt[r], positivesAt[r], (double) positivesAt[r] / itemsAt[r]));
            }
            return new Result(
                    RankingConfigs.INLINE.equals(config.id()) ? null : config.id(),
                    config.version(),
                    from,
                    to,
                    explorationOnly,
                    n,
                    sessions,
                    positives,
                    sessionsWithPositive,
                    RankingMetrics.auc(s, l),
                    RankingMetrics.logLoss(s, l),
                    sessionsWithPositive == 0 ? null : ndcgSum / sessionsWithPositive,
                    byPosition,
                    sampleFraction < 1,
                    sampleFraction);
        }
    }
}
