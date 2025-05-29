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

    SignalsPurge(EventRepository events) {
        this.events = events;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void purge(UUID userId) {
        events.anonymise(userId);
    }
}
