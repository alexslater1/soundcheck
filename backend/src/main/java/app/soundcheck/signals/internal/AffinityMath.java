package app.soundcheck.signals.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * The arithmetic of affinities: a score in [0, 1] that halves every 14 days without new
 * engagement and moves by a fixed amount per engagement. Pure functions, so the rules can be tested
 * without a database.
 */
public final class AffinityMath {

    public static final Duration HALF_LIFE = Duration.ofDays(14);

    /** How much one event moves the affinity for the post's author, artists and genres. */
    static final Map<String, Double> DELTA = Map.of(
            "LIKE", 0.10,
            "UNLIKE", -0.10,
            "COMMENT", 0.12,
            "SHARE", 0.15,
            "COMPLETE", 0.05,
            "WATCH_PROGRESS", 0.03, // only when the fraction is at least 0.75
            "PROFILE_TAP", 0.04,
            "GIG_TAP", 0.04,
            "HIDE", -0.20);

    /** One post cannot move an affinity by more than this in one batch, whatever was done to it. */
    static final double MAX_PER_POST = 0.30;

    private AffinityMath() {}

    /** The score as it stands at {@code now}: halved for every {@link #HALF_LIFE} since it was written. */
    public static double decayed(double score, Instant updatedAt, Instant now) {
        long elapsed = Duration.between(updatedAt, now).toMillis();
        if (elapsed <= 0) {
            return score;
        }
        return score * Math.pow(0.5, (double) elapsed / HALF_LIFE.toMillis());
    }

    /** {@code clamp(score·decay + Δ)}: the stored score after new engagement worth {@code delta}. */
    public static double next(double score, Instant updatedAt, Instant now, double delta) {
        return clamp(decayed(score, updatedAt, now) + delta);
    }

    /** What a set of events on one post is worth: the sum of their deltas, bounded per post. */
    static double deltaFor(Map<String, Long> eventCounts) {
        double sum = 0;
        for (Map.Entry<String, Long> entry : eventCounts.entrySet()) {
            sum += DELTA.getOrDefault(entry.getKey(), 0.0) * entry.getValue();
        }
        return Math.max(-MAX_PER_POST, Math.min(MAX_PER_POST, sum));
    }

    static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
