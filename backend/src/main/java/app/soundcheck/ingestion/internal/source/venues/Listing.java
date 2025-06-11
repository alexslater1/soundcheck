package app.soundcheck.ingestion.internal.source.venues;

import app.soundcheck.ingestion.internal.source.TimePrecision;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One event as an adapter read it from a venue's site, before it becomes a {@code SourceGig}. Times are
 * UK local, as venues write them. {@code acts} are the site's own act fields; empty when the site has
 * none, and then the title is parsed (structured fields first, the title as a fallback).
 */
public record Listing(
        String eventId,
        @Nullable String url,
        String title,
        LocalDateTime start,
        TimePrecision precision,
        List<String> acts,
        @Nullable String ticketUrl,
        @Nullable BigDecimal priceMin,
        @Nullable BigDecimal priceMax,
        @Nullable String imageUrl,
        boolean cancelled) {}
