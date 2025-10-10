package app.soundcheck.feed.internal;

import static app.soundcheck.shared.jooq.feed.Tables.POST_STAT_HOURLY;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The posts with the most engagement in the last 48 hours, shared by every viewer and reloaded when older
 * than {@code soundcheck.feed.trending-pool-ttl} (60 s). The TRENDING generator joins it instead
 * of aggregating {@code post_stats_hourly} on every For You first page: the aggregate is the same for
 * everyone, only the viewer's filters (own, blocked, hidden, private authors) differ, and those stay in
 * the candidate statement.
 */
@Component
public class TrendingPool {

    /** How many posts the pool holds: ten times TRENDING's default cap, so a viewer's filters rarely bite. */
    static final int SIZE = 2_000;

    static final Duration WINDOW = CandidateRepository.TRENDING_WINDOW;

    /** The pool: post ids and their engagement sums, most engaged first (parallel arrays, for unnest). */
    public record Top(UUID[] postIds, Long[] engagements) {
        static final Top EMPTY = new Top(new UUID[0], new Long[0]);

        public boolean isEmpty() {
            return postIds.length == 0;
        }
    }

    private record Loaded(Instant at, Top top) {}

    private final Function<Instant, Top> loader;
    private final Duration ttl;
    private final ReentrantLock reload = new ReentrantLock();
    private volatile @Nullable Loaded loaded;

    @Autowired
    TrendingPool(DSLContext dsl, @Value("${soundcheck.feed.trending-pool-ttl:60s}") Duration ttl) {
        this(since -> load(dsl, since), ttl);
    }

    TrendingPool(Function<Instant, Top> loader, Duration ttl) {
        this.loader = loader;
        this.ttl = ttl;
    }

    /** The pool as of {@code now}: the shared copy while it is fresh, else one reload (one caller at a time). */
    public Top top(Instant now) {
        Loaded current = loaded;
        if (fresh(current, now)) {
            return current.top();
        }
        reload.lock();
        try {
            current = loaded;
            if (fresh(current, now)) {
                return current.top(); // another caller reloaded it while this one waited
            }
            Top top = loader.apply(now.minus(WINDOW).truncatedTo(ChronoUnit.HOURS));
            loaded = new Loaded(now, top);
            return top;
        } finally {
            reload.unlock();
        }
    }

    private boolean fresh(@Nullable Loaded current, Instant now) {
        return current != null
                && !ttl.isZero()
                && !now.isBefore(current.at())
                && now.isBefore(current.at().plus(ttl));
    }

    /**
     * From the start of the hour the window begins in (the buckets are whole hours). Expected index: the
     * post_stats_hourly primary key (hour, post_id), a range on hour.
     */
    private static Top load(DSLContext dsl, Instant since) {
        Field<Long> sum = DSL.sum(POST_STAT_HOURLY.ENGAGEMENTS).cast(Long.class);
        List<org.jooq.Record2<UUID, Long>> rows = dsl.select(POST_STAT_HOURLY.POST_ID, sum)
                .from(POST_STAT_HOURLY)
                .where(POST_STAT_HOURLY.HOUR.ge(since.atOffset(ZoneOffset.UTC)))
                .groupBy(POST_STAT_HOURLY.POST_ID)
                .having(sum.gt(0L))
                .orderBy(sum.desc(), POST_STAT_HOURLY.POST_ID.desc())
                .limit(SIZE)
                .fetch();
        if (rows.isEmpty()) {
            return Top.EMPTY;
        }
        return new Top(
                rows.stream().map(r -> r.value1()).toArray(UUID[]::new),
                rows.stream().map(r -> r.value2()).toArray(Long[]::new));
    }
}
