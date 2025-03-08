package app.soundcheck.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

/** Cursor round-trip as a property, plus the rejection of anything that is not a cursor. */
class CursorTest {

    /** Any instant between 1970 and 2100 with any nanosecond, any UUID: encode then decode is the identity. */
    @Property(tries = 500)
    void roundTrips(
            @ForAll @LongRange(min = 0, max = 4_102_444_800L) long epochSecond,
            @ForAll @LongRange(min = 0, max = 999_999_999L) long nanos,
            @ForAll long most,
            @ForAll long least) {
        Cursor cursor = new Cursor(Instant.ofEpochSecond(epochSecond, nanos), new UUID(most, least));

        String encoded = cursor.encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+"); // URL-safe, no padding
        assertThat(Cursor.decode(encoded)).isEqualTo(cursor);
    }

    @Property(tries = 300)
    void anythingElseIsRejectedNotCrashed(@ForAll String garbage) {
        try {
            Cursor decoded = Cursor.decode(garbage);
            // The only strings that decode are blank ones (first page) or genuine cursors.
            assertThat(decoded == null || decoded.encode().equals(garbage)).isTrue();
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo("CURSOR_INVALID");
            assertThat(e.status().value()).isEqualTo(400);
        }
    }

    @Test
    void blankMeansFirstPageAndMalformedPayloadsAreInvalid() {
        assertThat(Cursor.decode(null)).isNull();
        assertThat(Cursor.decode("  ")).isNull();
        for (String json : new String[] {
            "[]",
            "[\"2026-10-03T19:00:00Z\"]",
            "[\"not a time\",\"" + UUID.randomUUID() + "\"]",
            "[\"2026-10-03T19:00:00Z\",\"not-a-uuid\"]",
            "{\"at\":1}"
        }) {
            String encoded =
                    Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> Cursor.decode(encoded))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo("CURSOR_INVALID"));
        }
    }

    @Test
    void limitDefaultsToTwentyAndIsClampedToOneToFifty() {
        assertThat(Cursor.limit(null)).isEqualTo(20);
        assertThat(Cursor.limit(0)).isEqualTo(1);
        assertThat(Cursor.limit(-5)).isEqualTo(1);
        assertThat(Cursor.limit(7)).isEqualTo(7);
        assertThat(Cursor.limit(500)).isEqualTo(50);
    }
}
