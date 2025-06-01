package app.soundcheck.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A gig in a list. {@code distanceKm} is present when the request had an origin (explicit
 * coordinates or the viewer's home city); {@code myAttendance} when the viewer has marked the gig.
 * {@code userAdded} marks a gig a user added because no source listed it.
 */
public record GigSummary(
        UUID id,
        String title,
        Instant startsAt,
        @Schema(allowableValues = {"SCHEDULED", "POSTPONED", "RESCHEDULED", "CANCELLED"}) String status,
        VenueSummary venue,
        ArtistSummary headliner,
        List<ArtistSummary> support,
        @Schema(nullable = true) String imageUrl,
        @Schema(nullable = true) String ticketUrl,
        @Schema(nullable = true) BigDecimal priceMin,
        @Schema(nullable = true) BigDecimal priceMax,
        @Schema(nullable = true) String currency,
        @Schema(
                        nullable = true,
                        allowableValues = {"INTERESTED", "GOING", "WENT"})
                String myAttendance,
        @Schema(nullable = true) Double distanceKm,
        @Schema(description = "Added by a fan, not from a ticketing source") boolean userAdded) {}
