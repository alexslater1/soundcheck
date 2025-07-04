package app.soundcheck.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * A venue in a list, plus {@code coverageNote}: for a venue whose own listings are not
 * read, why its gigs here may be incomplete; null for every other venue.
 */
public record VenueSummary(
        UUID id,
        String name,
        @Schema(nullable = true) String city,
        @Schema(nullable = true) GeoPoint location,
        @Schema(nullable = true) String coverageNote) {}
