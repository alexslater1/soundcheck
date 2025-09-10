package app.soundcheck.ingestion.internal;

import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the runs that the console's "Run now" queued. The API process only inserts a QUEUED row;
 * this poller, in a process with the {@code scheduler} role, claims the oldest one every 15 seconds and
 * runs it through {@link IngestionService#runQueued}. On AWS the API and the scheduler can be different
 * tasks, so the request that queues a run never runs it.
 *
 * <p>ShedLock lets one scheduler instance poll at a time; the conditional claim in {@link IngestionRuns#claim}
 * is the second line, should two pollers ever read the same row. Switched off in tests
 * ({@code soundcheck.ingestion.queue.enabled: false}), which call {@link #runNext()} themselves.
 */
@Component
@ConditionalOnRole(Roles.SCHEDULER)
class IngestionQueue {

    private final IngestionService service;
    private final IngestionRuns runs;
    private final boolean enabled;

    IngestionQueue(
            IngestionService service,
            IngestionRuns runs,
            @Value("${soundcheck.ingestion.queue.enabled:true}") boolean enabled) {
        this.service = service;
        this.runs = runs;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 15, initialDelay = 15, timeUnit = TimeUnit.SECONDS)
    @SchedulerLock(name = "ingestion-queued", lockAtMostFor = "PT2H")
    void poll() {
        if (enabled) {
            runNext();
        }
    }

    /** Runs the run queued first, if there is one; empty when the queue is empty or another poller won it. */
    Optional<IngestionService.RunResult> runNext() {
        return runs.oldestQueued().flatMap(service::runQueued);
    }
}
