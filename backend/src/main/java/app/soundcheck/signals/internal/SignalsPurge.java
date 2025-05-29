package app.soundcheck.signals.internal;

import app.soundcheck.identity.AccountPurgeStep;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signals' part of an account purge: the account's events are anonymised, not deleted (the
 * counters built from them stay true), and its affinities are removed.
 */
@Component
class SignalsPurge implements AccountPurgeStep {

    private final EventRepository events;
    private final EventLog log;

    SignalsPurge(EventRepository events, EventLog log) {
        this.events = events;
        this.log = log;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void purge(UUID userId) {
        // Wait for a running affinity rollup, and keep the next one out until this commits: a rollup
        // that had already read this user's engagement would otherwise write their affinities back.
        log.watermarkForUpdate(AffinityRollup.NAME);
        events.anonymise(userId);
    }
}
