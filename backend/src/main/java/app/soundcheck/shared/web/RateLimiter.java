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
 *
 * <p>Keys are attacker-controlled (any IP, any email), so the map is bounded: once it holds
 * {@link #MAX_BUCKETS} entries, full (idle) buckets are dropped, and if that is not enough the map is
 * cleared — a short, safe reset rather than unbounded heap growth.
 */
@Component
public class RateLimiter {

    static final int MAX_BUCKETS = 100_000;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Consumes one token from {@code key}'s bucket; empty when allowed, else how long to wait. */
    public Optional<Duration> consume(String key, long capacityPerPeriod, Duration period) {
        if (buckets.size() >= MAX_BUCKETS) {
            evictIdle(capacityPerPeriod);
        }
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

    private void evictIdle(long capacity) {
        buckets.entrySet().removeIf(e -> e.getValue().getAvailableTokens() >= capacity);
        if (buckets.size() >= MAX_BUCKETS) {
            buckets.clear();
        }
    }
}
