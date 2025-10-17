package app.soundcheck.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Cursor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

/** The gig-order cursor of a profile: it round-trips, and a cursor of the other order is refused. */
class GigDayCursorTest {

    @Property(tries = 300)
    void roundTrips(
            @ForAll @LongRange(min = -25_567, max = 73_000) long epochDay,
            @ForAll @LongRange(min = 0, max = 4_102_444_800L) long epochSecond,
            @ForAll long most,
            @ForAll long least) {
        GigDayCursor cursor = new GigDayCursor(
                LocalDate.ofEpochDay(epochDay), Instant.ofEpochSecond(epochSecond), new UUID(most, least));

        String encoded = cursor.encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(GigDayCursor.decode(encoded)).isEqualTo(cursor);
    }

    @Test
    void aPostWithNoGigPagesAfterEveryGig() {
        PostRow noGig = new PostRow(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                null,
                "PUBLISHED",
                0,
                0,
                Instant.EPOCH,
                Instant.EPOCH);

        assertThat(GigDayCursor.after(noGig, Instant.EPOCH).day()).isEqualTo(GigDayCursor.NO_DAY);
    }

    @Test
    void aCursorOfThePostedOrderIsRefused() {
        String posted = new Cursor(Instant.parse("2026-10-06T12:00:00Z"), UUID.randomUUID()).encode();

        assertThatThrownBy(() -> GigDayCursor.decode(posted))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("CURSOR_INVALID"));
        assertThatThrownBy(() -> GigDayCursor.decode("garbage")).isInstanceOf(ApiException.class);
        assertThat(GigDayCursor.decode(" ")).isNull();
    }
}
