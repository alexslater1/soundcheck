package app.soundcheck.ingestion.internal;

import app.soundcheck.ingestion.internal.source.GigSource;
import app.soundcheck.ingestion.internal.source.venues.VenueCoverageNotes;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

    private static final Logger log = LoggerFactory.getLogger(IngestionSchedule.class);

    private final IngestionService service;
    private final IngestionRuns runs;
    private final List<GigSource> sources;
    private final IngestionGuard guard;
    private final VenueCoverageNotes coverageNotes;
    private final Duration payloadRetention;
    private final Clock clock;

    IngestionSchedule(
            IngestionService service,
            IngestionRuns runs,
            List<GigSource> sources,
            IngestionGuard guard,
            VenueCoverageNotes coverageNotes,
            IngestionProperties properties,
            Clock clock) {
        this.service = service;
        this.sources = sources;
        this.guard = guard;
        this.coverageNotes = coverageNotes;
        this.runs = runs;
        this.payloadRetention = properties.payloadRetention();
        this.clock = clock;
    }

    /** Daily at 05:10 Europe/London, every configured source in turn. */
    @Scheduled(cron = "0 10 5 * * *", zone = "Europe/London")
    @SchedulerLock(name = "ingestion-daily", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    void ingestDaily() {
        applyCoverageNotes();
        for (GigSource source : sources) {
            try {
                guard.check(source);
                service.run(source.name(), "SCHEDULE");
            } catch (ApiException | IllegalStateException e) {
                log.warn("Scheduled ingestion of {} skipped: {}", source.name(), e.getMessage());
            }
        }
    }

    /** The "we don't read this venue's own listings" notes, as soon as the scheduler starts. */
    @EventListener(ApplicationReadyEvent.class)
    void applyCoverageNotes() {
        try {
            coverageNotes.apply();
        } catch (RuntimeException e) {
            log.warn("Coverage notes not applied: {}", e.toString()); // never stops a start or a run
        }
    }

    /** Daily at 04:40 Europe/London, before the ingestion run. */
    @Scheduled(cron = "0 40 4 * * *", zone = "Europe/London")
    @SchedulerLock(name = "ingestion-prune-payloads", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    void prunePayloads() {
        int deleted = runs.prunePayloads(Instant.now(clock).minus(payloadRetention));
        log.info("Pruned {} raw payloads older than {} days", deleted, payloadRetention.toDays());
    }
}
