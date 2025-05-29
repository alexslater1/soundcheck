package app.soundcheck.feed.internal;

import app.soundcheck.identity.AccountPurgeStep;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feed's part of an account purge: what was served to the account stays as training data
 * under the tombstone id, and its open feed sessions are removed.
 */
@Component
class FeedPurge implements AccountPurgeStep {

    private final ServedItemLog served;
    private final FeedSessionStore sessions;

    FeedPurge(ServedItemLog served, FeedSessionStore sessions) {
        this.served = served;
        this.sessions = sessions;
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void purge(UUID userId) {
        served.anonymise(userId);
        sessions.deleteOf(userId);
    }
}
