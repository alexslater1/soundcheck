package app.soundcheck.content.internal;

import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A safety net under the event-driven publish: once a minute, posts that have waited more than half a
 * minute are checked against their clip's actual status. Events can be missed in two ways the listener
 * cannot fix by itself: the post was created in the same instant the clip became READY (the listener
 * ran before the post was committed), or the publishing process died before the listener ran and the
 * resubmission has not happened yet.
 */
@Component
@ConditionalOnRole(Roles.SCHEDULER)
class PendingPostReconciler {

    private static final Logger log = LoggerFactory.getLogger(PendingPostReconciler.class);
    static final Duration OLDER_THAN = Duration.ofSeconds(30);

    private final PostService posts;
    private final Clock clock;

    PendingPostReconciler(PostService posts, Clock clock) {
        this.posts = posts;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    @SchedulerLock(name = "pending-post-reconcile", lockAtMostFor = "PT5M")
    void reconcile() {
        int changed = posts.reconcilePending(Instant.now(clock).minus(OLDER_THAN), 200);
        if (changed > 0) {
            log.info("reconciled {} posts whose media event was missed", changed);
        }
    }
}
