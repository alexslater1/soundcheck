package app.soundcheck.shared.web;

import static app.soundcheck.shared.jooq.shared.Tables.RATE_LIMIT_BUCKET;

import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hourly: deletes rate-limit buckets nobody has used for {@link #IDLE}. Keys are attacker-chosen
 * (any IP, any email), so rows would otherwise only grow. A bucket idle for longer than its period has
 * refilled to full, which is exactly what a missing row means to Bucket4j, so deleting it changes no
 * limit; {@link RateLimiter#MAX_PERIOD} (one day) caps every period, and the idle threshold is twice
 * that. The cut-off is the database's clock, the same one the trigger stamps {@code updated_at} with.
 * Expected index: rate_limit_bucket_updated.
 */
@Component
@ConditionalOnRole(Roles.SCHEDULER)
public class RateLimitBucketCleanup {

    static final Duration IDLE = RateLimiter.MAX_PERIOD.multipliedBy(2);

    private static final Logger log = LoggerFactory.getLogger(RateLimitBucketCleanup.class);

    private final DSLContext dsl;

    RateLimitBucketCleanup(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @SchedulerLock(name = "rate-limit-bucket-cleanup", lockAtMostFor = "PT10M")
    void scheduled() {
        int deleted = deleteIdle();
        if (deleted > 0) {
            log.info("deleted {} idle rate-limit buckets", deleted);
        }
    }

    /** @return how many buckets were deleted */
    @Transactional
    public int deleteIdle() {
        return dsl.deleteFrom(RATE_LIMIT_BUCKET)
                .where(DSL.condition(
                        "{0} < now() - make_interval(secs => {1})",
                        RATE_LIMIT_BUCKET.UPDATED_AT, DSL.val((double) IDLE.toSeconds())))
                .execute();
    }
}
