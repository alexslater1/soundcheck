package app.soundcheck.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class MutableClockTest {

    private static final ZoneId UK = ZoneId.of("Europe/London");

    @Test
    void aZonedViewKeepsItsZoneAndFollowsTheOffset() {
        MutableClock clock = new MutableClock();
        var uk = clock.withZone(UK);

        assertThat(uk.getZone()).isEqualTo(UK);
        assertThat(LocalDate.now(uk)).isEqualTo(LocalDate.now(UK));

        clock.advance(Duration.ofDays(3));
        assertThat(LocalDate.now(uk)).isEqualTo(LocalDate.now(UK).plusDays(3));
        clock.reset();
        assertThat(LocalDate.now(uk)).isEqualTo(LocalDate.now(UK));
    }
}
