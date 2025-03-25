package app.soundcheck.shared.outbox;

import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The retry half of the outbox. An event published inside a transaction is stored in
 * {@code shared.event_publication} by that transaction; each listener's delivery is marked complete only
 * when the listener returns normally. Deliveries that failed (say, SQS was unreachable) or were cut
 * short by a crash stay incomplete, and are tried again here every minute, and at start-up
 * ({@code republish-outstanding-events-on-restart}). Listeners therefore see an event at least once and
 * must be idempotent.
 */
@Component
@ConditionalOnRole(Roles.SCHEDULER)
class OutboxResubmitter {

    /** Younger publications may simply still be running. */
    static final Duration OLDER_THAN = Duration.ofMinutes(1);

    private final IncompleteEventPublications incomplete;

    OutboxResubmitter(IncompleteEventPublications incomplete) {
        this.incomplete = incomplete;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    @SchedulerLock(name = "outbox-resubmit", lockAtMostFor = "PT5M")
    void resubmit() {
        incomplete.resubmitIncompletePublicationsOlderThan(OLDER_THAN);
    }
}
