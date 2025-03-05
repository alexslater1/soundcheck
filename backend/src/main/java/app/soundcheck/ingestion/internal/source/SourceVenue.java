package app.soundcheck.ingestion.internal.source;

import org.jspecify.annotations.Nullable;

/** A venue as the source describes it. Coordinates are missing for some venues. */
public record SourceVenue(
        String externalId,
        String name,
        @Nullable String address,
        @Nullable String city,
        @Nullable String postcode,
        @Nullable Double lat,
        @Nullable Double lng,
        String timezone) {}
