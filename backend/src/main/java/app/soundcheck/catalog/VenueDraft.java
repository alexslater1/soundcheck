package app.soundcheck.catalog;

import org.jspecify.annotations.Nullable;

/** A venue to create ({@code catalog.venue}); {@code name_norm} is derived by the catalog. */
public record VenueDraft(
        String name,
        @Nullable String address,
        @Nullable String city,
        @Nullable String postcode,
        @Nullable Double lat,
        @Nullable Double lng,
        String timezone) {}
