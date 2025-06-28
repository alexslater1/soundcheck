package app.soundcheck.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.Parallel;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The buckets live in PostgreSQL, so every API process draws on one budget per key.
 * Two limiters with their own pools stand in for two API tasks.
 */
class RateLimiterPostgresIT extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterPostgresIT.class);
    private static final Duration MINUTE = Duration.ofMinutes(1);

    @Autowired
    RateLimiter applicationLimiter;

    @Autowired
    DataSource dataSource;

    @Autowired
    DSLContext dsl;

    @Test
    void theApplicationCountsInPostgres() {
        assertThat(applicationLimiter.storeName()).isEqualTo("postgres");
        String key = "it:" + UUID.randomUUID();
        assertThat(applicationLimiter.consume(key, 3, MINUTE)).isEmpty();
        assertThat(dsl.fetchExists(
                        dsl.selectOne().from("shared.rate_limit_bucket").where("id = ?", key + "|3/PT1M")))
                .isTrue();
    }

    @Test
    void twoApiProcessesShareOneBudget() {
        RateLimiter taskA = new RateLimiter(dataSource);
        RateLimiter taskB = new RateLimiter(dataSource);
        try {
            String key = "it:" + UUID.randomUUID();
            assertThat(taskA.consume(key, 3, MINUTE)).isEmpty();
            assertThat(taskB.consume(key, 3, MINUTE)).isEmpty();
            assertThat(taskA.consume(key, 3, MINUTE)).isEmpty();
            assertThat(taskB.consume(key, 3, MINUTE))
                    .as("the budget is spent, whichever task asks")
                    .isPresent();
            assertThat(taskA.consume(key, 3, MINUTE)).isPresent();
        } finally {
            taskA.destroy();
            taskB.destroy();
        }
    }

    @Test
    void simultaneousRequestsNeverTakeMoreThanTheCapacity() throws Exception {
        RateLimiter taskB = new RateLimiter(dataSource);
        try {
            String key = "it:" + UUID.randomUUID();
            List<Boolean> allowed = Parallel.run(20, i -> (i % 2 == 0 ? applicationLimiter : taskB)
                    .consume(key, 10, MINUTE)
                    .isEmpty());
            assertThat(allowed).filteredOn(Boolean::booleanValue).hasSize(10);
        } finally {
            taskB.destroy();
        }
    }

    @Test
    void idleBucketsAreDeletedAndRecentOnesKept() {
        String idle = "it:idle:" + UUID.randomUUID();
        String recent = "it:recent:" + UUID.randomUUID();
        dsl.execute(
                "insert into shared.rate_limit_bucket (id, state, updated_at) values (?, null, now() - interval '3 days')",
                idle);
        applicationLimiter.consume(recent, 5, MINUTE);
        String recentRow = recent + "|5/PT1M";

        new RateLimitBucketCleanup(dsl).deleteIdle();

        assertThat(dsl.fetchExists(
                        dsl.selectOne().from("shared.rate_limit_bucket").where("id = ?", idle)))
                .isFalse();
        assertThat(dsl.fetchExists(
                        dsl.selectOne().from("shared.rate_limit_bucket").where("id = ?", recentRow)))
                .isTrue();
    }

    /** What a limit check costs: one short transaction (SELECT … FOR UPDATE, UPDATE), measured here. */
    @Test
    void aLimitCheckIsOneShortTransaction() {
        String key = "it:cost:" + UUID.randomUUID();
        applicationLimiter.consume(key, 10_000, MINUTE); // the first check also inserts the row
        int checks = 200;
        long start = System.nanoTime();
        for (int i = 0; i < checks; i++) {
            applicationLimiter.consume(key, 10_000, MINUTE);
        }
        double averageMs = (System.nanoTime() - start) / 1e6 / checks;
        log.info("rate-limit check against PostgreSQL: {} ms on average over {} checks", averageMs, checks);
        assertThat(averageMs).as("average milliseconds per check").isLessThan(100);
    }
}
