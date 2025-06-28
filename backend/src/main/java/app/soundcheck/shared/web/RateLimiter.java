package app.soundcheck.shared.web;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.jdbc.BucketTableSettings;
import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper;
import io.github.bucket4j.distributed.jdbc.SQLProxyConfigurationBuilder;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.local.LocalBucket;
import io.github.bucket4j.postgresql.PostgreSQLSelectForUpdateBasedProxyManager;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Token-bucket rate limits on Bucket4j. Every limit is a bucket per key ("auth:ip:…",
 * "user:…") holding {@code capacity} tokens that refill evenly over {@code period}; a request takes one.
 *
 * <p>The buckets live in PostgreSQL ({@code shared.rate_limit_bucket}), so all API
 * processes share one budget per key: with N tasks a limit stays the limit, not N times it. Bucket4j
 * reads and writes a bucket inside one short transaction ({@code SELECT … FOR UPDATE}, then
 * {@code UPDATE}), so concurrent requests from different processes cannot both take the last token. It
 * uses its own small connection pool: several limits are checked inside a service's transaction (login,
 * upload), and taking a second connection from the main pool there could starve it under load.
 *
 * <p>Without a database (a web slice test, a plain unit test) the buckets are kept in memory instead.
 *
 * <p>A bucket's key includes its capacity and period, so a changed limit starts a fresh bucket rather
 * than inheriting the old one's state.
 */
@Component
public class RateLimiter implements DisposableBean {

    /** The longest period a limit may use: {@link RateLimitBucketCleanup} deletes rows idle twice this long. */
    public static final Duration MAX_PERIOD = Duration.ofDays(1);

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final Store store;

    /** In memory, per process: for tests without a database. */
    public RateLimiter() {
        this(new InMemoryStore());
    }

    /** PostgreSQL when the application has a database, else in memory. */
    @Autowired
    public RateLimiter(ObjectProvider<DataSource> dataSource) {
        this(storeFor(dataSource.getIfAvailable()));
    }

    /** PostgreSQL through its own pool on the same database as {@code main} (one per API process). */
    RateLimiter(DataSource main) {
        this(PostgresStore.on(main));
    }

    private RateLimiter(Store store) {
        this.store = store;
    }

    private static Store storeFor(DataSource dataSource) {
        if (dataSource == null) {
            log.info("Rate limits are counted in memory (no database in this context)");
            return new InMemoryStore();
        }
        log.info("Rate limits are counted in PostgreSQL (shared.rate_limit_bucket)");
        return PostgresStore.on(dataSource);
    }

    /** Consumes one token from {@code key}'s bucket; empty when allowed, else how long to wait. */
    public Optional<Duration> consume(String key, long capacityPerPeriod, Duration period) {
        if (capacityPerPeriod < 1 || period.isZero() || period.isNegative() || period.compareTo(MAX_PERIOD) > 0) {
            throw new IllegalArgumentException("a limit needs a capacity ≥ 1 and a period up to " + MAX_PERIOD + ": "
                    + capacityPerPeriod + "/" + period);
        }
        String bucketKey = key + "|" + capacityPerPeriod + "/" + period;
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacityPerPeriod)
                        .refillGreedy(capacityPerPeriod, period)
                        .build())
                .build();
        ConsumptionProbe probe = store.tryConsume(bucketKey, configuration);
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
        return store.size();
    }

    /** {@code memory} or {@code postgres}: where this limiter counts. */
    public String storeName() {
        return store.name();
    }

    @Override
    public void destroy() {
        store.close();
    }

    private interface Store {

        ConsumptionProbe tryConsume(String key, BucketConfiguration configuration);

        int size();

        String name();

        default void close() {}
    }

    /**
     * Keys are attacker-controlled (any IP, any email), so the map is bounded: once it holds
     * {@link #MAX_BUCKETS} entries, full (idle) buckets are dropped, and if that is not enough the map is
     * cleared, a short safe reset rather than unbounded heap growth.
     */
    private static final class InMemoryStore implements Store {

        static final int MAX_BUCKETS = 100_000;

        private final Map<String, LocalBucket> buckets = new ConcurrentHashMap<>();

        @Override
        public ConsumptionProbe tryConsume(String key, BucketConfiguration configuration) {
            if (buckets.size() >= MAX_BUCKETS) {
                buckets.entrySet()
                        .removeIf(e -> e.getValue().getAvailableTokens()
                                >= e.getValue()
                                        .getConfiguration()
                                        .getBandwidths()[0]
                                        .getCapacity());
                if (buckets.size() >= MAX_BUCKETS) {
                    buckets.clear();
                }
            }
            LocalBucket bucket = buckets.computeIfAbsent(key, k -> {
                var builder = Bucket.builder();
                for (Bandwidth limit : configuration.getBandwidths()) {
                    builder.addLimit(limit);
                }
                return builder.build();
            });
            return bucket.tryConsumeAndReturnRemaining(1);
        }

        @Override
        public int size() {
            return buckets.size();
        }

        @Override
        public String name() {
            return "memory";
        }
    }

    private static final class PostgresStore implements Store {

        static final String TABLE = "shared.rate_limit_bucket";

        private final HikariDataSource pool;
        private final ProxyManager<String> buckets;

        private PostgresStore(HikariDataSource pool) {
            this.pool = pool;
            this.buckets = new PostgreSQLSelectForUpdateBasedProxyManager<>(SQLProxyConfigurationBuilder.builder()
                    .withTableSettings(BucketTableSettings.customSettings(TABLE, "id", "state"))
                    .withPrimaryKeyMapper(PrimaryKeyMapper.STRING)
                    .build(pool));
        }

        /**
         * A pool of its own on the main pool's database: at most four connections, none kept while idle,
         * and a short wait, since a limit check is three statements.
         */
        static PostgresStore on(DataSource main) {
            if (!(main instanceof HikariDataSource hikari)) {
                throw new IllegalStateException("rate limits need the Hikari pool's settings, got " + main.getClass());
            }
            HikariConfig config = new HikariConfig();
            config.setPoolName("rate-limits");
            config.setJdbcUrl(hikari.getJdbcUrl());
            config.setUsername(hikari.getUsername());
            config.setPassword(hikari.getPassword());
            if (hikari.getDriverClassName() != null) {
                config.setDriverClassName(hikari.getDriverClassName());
            }
            config.setMaximumPoolSize(4);
            config.setMinimumIdle(0);
            config.setIdleTimeout(Duration.ofSeconds(30).toMillis());
            config.setConnectionTimeout(Duration.ofSeconds(5).toMillis());
            return new PostgresStore(new HikariDataSource(config));
        }

        @Override
        public ConsumptionProbe tryConsume(String key, BucketConfiguration configuration) {
            return buckets.builder().build(key, () -> configuration).tryConsumeAndReturnRemaining(1);
        }

        @Override
        public int size() {
            try (Connection connection = pool.getConnection();
                    Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("select count(*) from " + TABLE)) {
                rows.next();
                return rows.getInt(1);
            } catch (SQLException e) {
                throw new IllegalStateException("could not count rate-limit buckets", e);
            }
        }

        @Override
        public String name() {
            return "postgres";
        }

        @Override
        public void close() {
            pool.close();
        }
    }
}
