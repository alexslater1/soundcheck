package app.soundcheck.catalog;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A note for a venue whose own listings are not read, and how to find it: its name or an alias
 * (compared normalised), within 25 km of central London, or only at postcodes starting with
 * {@code postcodePrefix} (e.g. "SW15") when the name is a common one ("The Half Moon").
 */
public record VenueNote(String name, List<String> aliases, @Nullable String postcodePrefix, String note) {

    public VenueNote {
        aliases = List.copyOf(aliases);
    }
}
