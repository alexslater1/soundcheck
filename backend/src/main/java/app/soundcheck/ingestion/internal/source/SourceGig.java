package app.soundcheck.ingestion.internal.source;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The common shape every source adapter maps to. Times are UTC; {@code artists} keeps the
 * billing order with the headliner first; {@code status} is one of the catalog's gig statuses.
 */
public record SourceGig(
        String source,
        String externalId,
        @Nullable String url,
        String title,
        Instant startsAt,
        @Nullable Instant doorsAt,
        String status,
        @Nullable String ticketUrl,
        @Nullable BigDecimal priceMin,
        @Nullable BigDecimal priceMax,
        @Nullable String currency,
        @Nullable String imageUrl,
        SourceVenue venue,
        List<SourceArtist> artists,
        /** How much {@code startsAt} can be trusted: a show time, a doors time, or only the day. */
        TimePrecision timePrecision) {

    /** A listing with a real show time (ticketing sources). */
    public SourceGig(
            String source,
            String externalId,
            @Nullable String url,
            String title,
            Instant startsAt,
            @Nullable Instant doorsAt,
            String status,
            @Nullable String ticketUrl,
            @Nullable BigDecimal priceMin,
            @Nullable BigDecimal priceMax,
            @Nullable String currency,
            @Nullable String imageUrl,
            SourceVenue venue,
            List<SourceArtist> artists) {
        this(
                source,
                externalId,
                url,
                title,
                startsAt,
                doorsAt,
                status,
                ticketUrl,
                priceMin,
                priceMax,
                currency,
                imageUrl,
                venue,
                artists,
                TimePrecision.EXACT);
    }
}
