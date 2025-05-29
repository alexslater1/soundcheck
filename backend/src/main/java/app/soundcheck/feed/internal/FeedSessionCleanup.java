package app.soundcheck.feed.internal;

import static app.soundcheck.shared.jooq.feed.Tables.FEED_SESSION;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every five minutes: deletes feed sessions past their expiry.
 * A session lives 30 minutes; reading one checks the expiry itself, so this only keeps the table small.
 * {@code @Scheduled} only fires where scheduling is enabled, that is in the scheduler role.
 * Expected index: feed_session_expires.
 */
@Component
public class FeedSessionCleanup {

    private static final Logger log = LoggerFactory.getLogger(FeedSessionCleanup.class);

    private final DSLContext dsl;
    private final Clock clock;

    FeedSessionCleanup(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
    @SchedulerLock(name = "feed-session-cleanup", lockAtMostFor = "PT5M")
    void scheduled() {
        int deleted = deleteExpired();
        if (deleted > 0) {
            log.info("deleted {} expired feed sessions", deleted);
        }
    }

    /** @return how many sessions were deleted */
    @Transactional
    public int deleteExpired() {
        return dsl.deleteFrom(FEED_SESSION)
                .where(FEED_SESSION.EXPIRES_AT.lt(Instant.now(clock).atOffset(ZoneOffset.UTC)))
                .execute();
    }
}
