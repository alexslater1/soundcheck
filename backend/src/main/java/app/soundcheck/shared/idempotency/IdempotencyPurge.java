package app.soundcheck.shared.idempotency;

import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly: forgets idempotency keys older than 24 hours, on one scheduler instance. */
@Component
@ConditionalOnRole(Roles.SCHEDULER)
class IdempotencyPurge {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurge.class);

    private final IdempotencyKeys keys;

    IdempotencyPurge(IdempotencyKeys keys) {
        this.keys = keys;
    }

    @Scheduled(cron = "0 17 * * * *")
    @SchedulerLock(name = "idempotency-purge", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    void purge() {
        int deleted = keys.purge();
        if (deleted > 0) {
            log.info("Purged {} idempotency keys older than 24 hours", deleted);
        }
    }
}
