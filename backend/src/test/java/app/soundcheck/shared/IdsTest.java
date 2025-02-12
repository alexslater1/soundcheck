package app.soundcheck.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/** UUIDv7 properties: version 7, RFC variant, unique, and non-decreasing in generation order. */
class IdsTest {

    @Property(tries = 50)
    void idsAreVersion7AndUniqueAndTimeOrdered(@ForAll @IntRange(min = 2, max = 500) int count) {
        Set<UUID> seen = new HashSet<>();
        UUID previous = null;
        for (int i = 0; i < count; i++) {
            UUID id = Ids.newId();
            assertThat(id.version()).isEqualTo(7);
            assertThat(id.variant()).isEqualTo(2);
            assertThat(seen.add(id)).as("duplicate id").isTrue();
            if (previous != null) {
                // Time-ordered: the 48-bit millisecond prefix never goes backwards within one generator.
                assertThat(id.getMostSignificantBits() >>> 16)
                        .isGreaterThanOrEqualTo(previous.getMostSignificantBits() >>> 16);
            }
            previous = id;
        }
    }
}
