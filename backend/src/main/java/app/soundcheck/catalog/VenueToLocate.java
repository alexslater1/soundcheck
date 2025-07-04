package app.soundcheck.catalog;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A venue with no coordinates yet, and what may locate it: its postcode, else its town. */
public record VenueToLocate(UUID id, String name, @Nullable String postcode, @Nullable String city) {}
