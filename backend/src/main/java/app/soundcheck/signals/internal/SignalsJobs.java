package app.soundcheck.signals.internal;

import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.signals.EngagementPriors.Prior;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The schedules of the signals module, on one scheduler instance at a time (ShedLock).
 * Each job is timed ({@code soundcheck.rollup.duration}, tag {@code job}); the rollups also count the
 * events they folded in ({@code soundcheck.rollup.events}).
 */
@Component
@ConditionalOnRole(Roles.SCHEDULER)
class SignalsJobs {

    private static final Logger log = LoggerFactory.getLogger(SignalsJobs.class);

    /**
     * The rollups read events that arrived at least this long ago. Events become visible when their
     * insert commits, not in key order; by now every insert stamped earlier has committed, so the
     * watermark never passes an event that is still on its way in.
     */
    static final Duration SETTLE = Duration.ofSeconds(5);

    /** At most this many batches per run, so one run cannot hold its lock for ever. */
    private static final int MAX_BATCHES = 20;

    private final PostStatsRollup postStats;
    private final AffinityRollup affinity;
    private final GlobalPriorRefresh prior;
    private final MeterRegistry meters;
    private final Clock clock;

    SignalsJobs(
            PostStatsRollup postStats,
            AffinityRollup affinity,
            GlobalPriorRefresh prior,
            MeterRegistry meters,
            Clock clock) {
        this.postStats = postStats;
        this.affinity = affinity;
        this.prior = prior;
        this.meters = meters;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    @SchedulerLock(name = "post-stats-rollup", lockAtMostFor = "PT10M")
    void rollUpPostStats() {
        timed("post_stats", () -> {
            int total = 0;
            for (int batch = 0; batch < MAX_BATCHES; batch++) {
                int events = postStats.rollUpBatch(Instant.now(clock).minus(SETTLE));
                total += events;
                if (events < PostStatsRollup.BATCH) {
                    break;
                }
            }
            if (total > 0) {
                meters.counter("soundcheck.rollup.events", "job", "post_stats").increment(total);
            }
        });
    }

    @Scheduled(fixedDelayString = "${soundcheck.signals.affinity-rollup-interval:PT10M}", initialDelayString = "PT1M")
    @SchedulerLock(name = "affinity-rollup", lockAtMostFor = "PT20M")
    void rollUpAffinities() {
        timed("affinity", () -> {
            for (int batch = 0; batch < MAX_BATCHES; batch++) {
                Instant now = Instant.now(clock);
                if (!affinity.rollUpBatch(now.minus(SETTLE), now)) {
                    break;
                }
            }
        });
    }

    @Scheduled(cron = "0 20 * * * *")
    @SchedulerLock(name = "global-prior-refresh", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    void refreshPrior() {
        timed("global_prior", () -> {
            Prior refreshed = prior.refresh();
            log.info("global engagement prior: mean {}, p99 {}", refreshed.meanRate(), refreshed.p99Rate());
        });
    }

    private void timed(String job, Runnable work) {
        Timer.builder("soundcheck.rollup.duration")
                .tag("job", job)
                .register(meters)
                .record(work);
    }
}
