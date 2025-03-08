package app.soundcheck.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record VenueSummary(
        UUID id, String name, @Schema(nullable = true) String city, @Schema(nullable = true) GeoPoint location) {}
