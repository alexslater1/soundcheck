package app.soundcheck.ingestion.internal.pastgigs;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * A past concert as setlist.fm records it. Shown to the fan as a candidate, never stored:
 * when the fan picks one, the app adds the gig through {@code POST /gigs} and only the setlist's id and
 * link are kept, as the gig's {@code SETLISTFM} listing. {@code lat}/{@code lng} are the city's
 * coordinates, not the venue's.
 */
public record PastGig(
        String setlistId,
        @Schema(nullable = true) String url,
        LocalDate date,
        String artistName,
        @Schema(nullable = true) String artistMbid,
        String venueName,
        @Schema(nullable = true) String cityName,
        @Schema(nullable = true) String countryCode,
        @Schema(nullable = true) Double lat,
        @Schema(nullable = true) Double lng,
        @Schema(nullable = true) String tour) {}
