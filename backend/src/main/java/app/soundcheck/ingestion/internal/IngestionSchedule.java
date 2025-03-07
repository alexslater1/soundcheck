package app.soundcheck.ingestion.internal;

import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled side of ingestion, only in processes with the {@code scheduler} role. ShedLock
 * makes each firing run on one instance; {@code lockAtLeastFor} stops a second instance with a slightly
 * late clock from running the same firing again.
 */
@Component
@ConditionalOnRole(Roles.SCHEDULER)
class IngestionSchedule {

    /** Raw payloads are pruned after 14 days. */
    static final Duration PAYLOAD_RETENTION = Duration.ofDays(14);

    private static final Logger log = LoggerFactory.getLogger(IngestionSchedule.class);

    private final IngestionService service;
    private final IngestionRuns runs;
    private final Clock clock;

    IngestionSchedule(IngestionService service, IngestionRuns runs, Clock clock) {
        this.service = service;
        this.runs = runs;
        this.clock = clock;
    }

    /** Daily at 05:10 Europe/London, every configured source in turn. */
    @Scheduled(cron = "0 10 5 * * *", zone = "Europe/London")
    @SchedulerLock(name = "ingestion-daily", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    void ingestDaily() {
        for (String source : service.sourceNames()) {
            try {
                service.run(source, "SCHEDULE");
            } catch (ApiException e) {
                log.warn("Scheduled ingestion of {} skipped: {}", source, e.getMessage());
            }
        }
    }

    /** Daily at 04:40 Europe/London, before the ingestion run. */
    @Scheduled(cron = "0 40 4 * * *", zone = "Europe/London")
    @SchedulerLock(name = "ingestion-prune-payloads", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    void prunePayloads() {
        int deleted = runs.prunePayloads(Instant.now(clock).minus(PAYLOAD_RETENTION));
        log.info("Pruned {} raw payloads older than {} days", deleted, PAYLOAD_RETENTION.toDays());
    }
}
