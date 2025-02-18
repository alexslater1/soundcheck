package app.soundcheck.shared.web;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Token-bucket rate limits on Bucket4j. Per-instance in-memory buckets — the documented
 * fallback for the Postgres-backed proxy manager; with N API instances the effective limit is
 * N× the configured one, which is acceptable for abuse protection.
 */
@Component
public class RateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Consumes one token from {@code key}'s bucket; empty when allowed, else how long to wait. */
    public Optional<Duration> consume(String key, long capacityPerPeriod, Duration period) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacityPerPeriod)
                        .refillGreedy(capacityPerPeriod, period)
                        .build())
                .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofNanos(Math.max(1, probe.getNanosToWaitForRefill())));
    }

    /** Throws {@code 429 RATE_LIMITED} with {@code Retry-After} when the limit is hit. */
    public void enforce(String key, long capacityPerPeriod, Duration period) {
        consume(key, capacityPerPeriod, period).ifPresent(wait -> {
            throw new RateLimitExceededException(wait, capacityPerPeriod);
        });
    }

    /** Number of live buckets (for tests and metrics). */
    public int size() {
        return buckets.size();
    }
}
