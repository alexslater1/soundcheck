package app.soundcheck.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Token buckets: N allowed per period, the (N+1)th waits; keys are independent. */
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
    }
}
