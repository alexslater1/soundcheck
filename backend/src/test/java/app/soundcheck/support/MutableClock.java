package app.soundcheck.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The application's {@link Clock} in integration tests: real UTC time plus an offset a test can move.
 * A test that calls {@link #advance} must call {@link #reset} afterwards (the Spring context, and so
 * this clock, is shared by every integration test class).
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    public void advance(Duration by) {
        offset.updateAndGet(current -> current.plus(by));
    }

    public void reset() {
        offset.set(Duration.ZERO);
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /**
     * A view of this clock in {@code zone}: the same moving instant, read in that zone. Returning
     * {@code this} (UTC) made {@code LocalDate.now(clock.withZone(UK))} the UTC date, so in the hour after
     * UK midnight in summer (23:00–00:00 UTC) a gig "on today" was refused as tomorrow's (PostWentIT, found
     * at 23:53 UTC). Production's {@code Clock.systemUTC().withZone(...)} never did that.
     */
    @Override
    public Clock withZone(ZoneId zone) {
        return zone.equals(getZone()) ? this : new Zoned(this, zone);
    }

    private static final class Zoned extends Clock {

        private final MutableClock source;
        private final ZoneId zone;

        private Zoned(MutableClock source, ZoneId zone) {
            this.source = source;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId other) {
            return source.withZone(other);
        }

        @Override
        public Instant instant() {
            return source.instant();
        }
    }
}
