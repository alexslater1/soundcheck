package app.soundcheck.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A gig's stored fields ({@code catalog.gig} and its line-up). {@code artistIds} is the bill
 * in order, headliner first. {@code contentHash} is computed by the caller over the normalised fields
 * so an unchanged gig can be skipped without comparing columns.
 */
public record GigDraft(
        String title,
        UUID venueId,
        Instant startsAt,
        @Nullable Instant doorsAt,
        String status,
        @Nullable String ticketUrl,
        @Nullable BigDecimal priceMin,
        @Nullable BigDecimal priceMax,
        @Nullable String currency,
        @Nullable String imageUrl,
        byte[] contentHash,
        List<UUID> artistIds) {}
