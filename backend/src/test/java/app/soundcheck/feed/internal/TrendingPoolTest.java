package app.soundcheck.feed.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The trending pool is loaded once per TTL and shared; a zero TTL reloads every time. */
class TrendingPoolTest {

    static final Instant T0 = Instant.parse("2026-10-06T10:30:15Z");

    final List<Instant> loads = new ArrayList<>();

    TrendingPool.Top load(Instant since) {
        loads.add(since);
        return new TrendingPool.Top(new UUID[] {UUID.randomUUID()}, new Long[] {(long) loads.size()});
    }

    @Test
    void aFreshPoolIsSharedAndAStaleOneReloadsFromTheWindowsStartHour() {
        TrendingPool pool = new TrendingPool(this::load, Duration.ofSeconds(60));

        TrendingPool.Top first = pool.top(T0);
        assertThat(pool.top(T0.plusSeconds(59))).isSameAs(first);
        assertThat(loads).containsExactly(Instant.parse("2026-10-04T10:00:00Z")); // 48 h back, whole hour

        TrendingPool.Top second = pool.top(T0.plusSeconds(60));
        assertThat(second).isNotSameAs(first);
        assertThat(loads).hasSize(2);
        // A clock that goes back (a test's MutableClock) never serves a pool loaded "in the future".
        assertThat(pool.top(T0.minusSeconds(1))).isNotSameAs(second);
        assertThat(loads).hasSize(3);
    }

    @Test
    void aZeroTtlReloadsOnEveryCall() {
        TrendingPool pool = new TrendingPool(this::load, Duration.ZERO);
        pool.top(T0);
        pool.top(T0);
        assertThat(loads).hasSize(2);
    }
}
