package app.soundcheck.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Token buckets (in memory here; the PostgreSQL store is RateLimiterPostgresIT): N allowed per period. */
class RateLimiterTest {

    final RateLimiter limiter = new RateLimiter();

    @Test
    void allowsCapacityThenDenies() {
        assertThat(limiter.consume("k", 2, Duration.ofMinutes(1))).isEmpty();
        assertThat(limiter.consume("k", 2, Duration.ofMinutes(1))).isEmpty();
        assertThat(limiter.consume("k", 2, Duration.ofMinutes(1))).isPresent();
        assertThat(limiter.consume("other", 2, Duration.ofMinutes(1))).isEmpty();
        assertThatThrownBy(() -> limiter.enforce("k", 2, Duration.ofMinutes(1)))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(e -> assertThat(((RateLimitExceededException) e).retryAfterSeconds())
                        .isBetween(1L, 60L));
        assertThat(limiter.size()).isEqualTo(2);
        assertThat(limiter.storeName()).isEqualTo("memory");
    }

    @Test
    void aChangedLimitStartsAFreshBucket() {
        assertThat(limiter.consume("k", 1, Duration.ofMinutes(1))).isEmpty();
        assertThat(limiter.consume("k", 1, Duration.ofMinutes(1))).isPresent();
        assertThat(limiter.consume("k", 2, Duration.ofMinutes(1))).isEmpty();
    }

    /** RateLimitBucketCleanup deletes rows idle for twice the longest period, so no limit may be longer. */
    @Test
    void periodsLongerThanADayAndEmptyLimitsAreRefused() {
        assertThatThrownBy(() -> limiter.consume("k", 1, Duration.ofDays(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.consume("k", 0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(limiter.consume("k", 20, RateLimiter.MAX_PERIOD)).isEmpty();
    }
}
