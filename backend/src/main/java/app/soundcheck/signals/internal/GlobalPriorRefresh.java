package app.soundcheck.signals.internal;

import app.soundcheck.signals.EngagementPriors.Prior;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes the global engagement prior: the mean engagement
 * rate over all posts, and the 99th percentile of the smoothed per-post rate, which ranking feature 8
 * uses as "as engaging as it gets".
 */
@Service
public class GlobalPriorRefresh {

    /**
     * Pseudo-impressions used for the percentile: the default {@code priorStrength}. A
     * ranking config may use another strength for the feature itself; the scale it divides by stays
     * the one computed here.
     */
    static final double PRIOR_STRENGTH = 20;

    /** How long hourly counters are kept: twice the longest window that reads them. */
    static final Duration HOURLY_RETENTION = Duration.ofDays(14);

    private final PostStatsRepository stats;
    private final Clock clock;

    GlobalPriorRefresh(PostStatsRepository stats, Clock clock) {
        this.stats = stats;
        this.clock = clock;
    }

    @Transactional
    public Prior refresh() {
        return stats.refreshPrior(PRIOR_STRENGTH, Instant.now(clock));
    }

    /**
     * Removes hourly counters nobody reads any more (candidate generation looks back seven days).
     *
     * @return how many rows were removed
     */
    @Transactional
    public int forgetOldHours() {
        return stats.deleteHourlyBefore(Instant.now(clock).minus(HOURLY_RETENTION));
    }
}
