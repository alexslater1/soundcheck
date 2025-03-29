package app.soundcheck.notifications.internal;

import static app.soundcheck.shared.jooq.notify.Tables.NOTIFICATION;

import app.soundcheck.identity.AccountPurgeStep;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notifications' part of an account purge: entries in other people's inboxes that name the purged user as
 * the actor. (The user's own inbox goes with the user row: {@code on delete cascade}.)
 */
@Component
class NotificationPurge implements AccountPurgeStep {

    private final DSLContext dsl;

    NotificationPurge(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void purge(UUID userId) {
        dsl.deleteFrom(NOTIFICATION).where(NOTIFICATION.ACTOR_ID.eq(userId)).execute();
    }
}
