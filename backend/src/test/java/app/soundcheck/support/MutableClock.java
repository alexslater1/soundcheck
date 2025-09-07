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

    private final AtomicReference<Duration> offset;
    private final ZoneId zone;

    public MutableClock() {
        this(new AtomicReference<>(Duration.ZERO), ZoneOffset.UTC);
    }

    /** The same offset seen in another zone (what {@link #withZone} returns). */
    private MutableClock(AtomicReference<Duration> offset, ZoneId zone) {
        this.offset = offset;
        this.zone = zone;
    }

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
        return zone;
    }

    /**
     * The same moving instant in {@code zone}. (Returning {@code this} made {@code LocalDate.now(clock.withZone(UK))}
     * the UTC date, so between midnight and 1 am in British Summer Time a gig on today's UK date read as
     * tomorrow's and PostWentIT failed.)
     */
    @Override
    public Clock withZone(ZoneId zone) {
        return zone.equals(this.zone) ? this : new MutableClock(offset, zone);
    }
}
