package app.soundcheck.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/** The test clock reads the same instant in any zone, as production's system clock does. */
class MutableClockTest {

    static final ZoneId UK = ZoneId.of("Europe/London");

    @Test
    void inTheHourAfterUkMidnightInSummerTheUkDateIsAlreadyTomorrow() {
        MutableClock clock = new MutableClock();
        // 23:30 UTC on 1 July is 00:30 BST on 2 July.
        Instant target = Instant.parse("2031-07-01T23:30:00Z");
        clock.advance(Duration.between(Instant.now(), target));

        assertThat(LocalDate.now(clock.withZone(UK))).isEqualTo(LocalDate.parse("2031-07-02"));
        assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.parse("2031-07-01"));
        assertThat(clock.withZone(UK).instant()).isCloseTo(target, within());
        assertThat(clock.withZone(UK).withZone(UK).getZone()).isEqualTo(UK);
    }

    @Test
    void theZonedViewFollowsTheOffset() {
        MutableClock clock = new MutableClock();
        java.time.Clock uk = clock.withZone(UK);
        Instant before = uk.instant();

        clock.advance(Duration.ofDays(3));

        assertThat(Duration.between(before, uk.instant())).isGreaterThanOrEqualTo(Duration.ofDays(3));
    }

    private static org.assertj.core.data.TemporalUnitOffset within() {
        return new org.assertj.core.data.TemporalUnitWithinOffset(5, ChronoUnit.SECONDS);
    }
}
