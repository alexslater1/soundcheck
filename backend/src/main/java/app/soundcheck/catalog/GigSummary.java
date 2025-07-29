package app.soundcheck.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A gig in a list. {@code distanceKm} is present when the request had an origin (explicit
 * coordinates or the viewer's home city); {@code myAttendance} when the viewer has marked the gig.
 * {@code userAdded} marks a gig a user added because no source listed it. A Going on a gig whose
 * day is over reads as Went ({@code wentUnconfirmed}).
 */
public record GigSummary(
        UUID id,
        String title,
        Instant startsAt,
        @Schema(
                        allowableValues = {"SCHEDULED", "POSTPONED", "RESCHEDULED", "CANCELLED", "UNLISTED"},
                        description = "UNLISTED: only a venue's own site listed it, and it has stopped")
                String status,
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
        @Schema(description = "Added by a fan, not from a ticketing source") boolean userAdded,
        @Schema(
                        description =
                                "myAttendance reads WENT only because the viewer said Going and the gig's day is over: Pending asks \"Did you go?\"")
                boolean wentUnconfirmed,
        @Schema(
                        description =
                                "Acts on the bill the viewer marked as missed; empty: they saw the whole bill, or have no Went")
                List<UUID> myActsMissed) {}
