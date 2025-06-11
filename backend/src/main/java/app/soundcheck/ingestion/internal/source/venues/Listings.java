package app.soundcheck.ingestion.internal.source.venues;

import app.soundcheck.ingestion.internal.resolve.TitleParser;
import app.soundcheck.ingestion.internal.source.SourceArtist;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceVenue;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.parser.Parser;
import org.jspecify.annotations.Nullable;

/** Shared conversions for venue adapters: listing → {@code SourceGig}, prices, HTML entities. */
public final class Listings {

    public static final String SOURCE = "VENUE";
    static final ZoneId UK = ZoneId.of("Europe/London");
    private static final Pattern MONEY = Pattern.compile("(\\d+(?:\\.\\d{1,2})?)");
    private static final Pattern POUNDS = Pattern.compile("£\\s*(\\d+(?:\\.\\d{1,2})?)");

    private Listings() {}

    /**
     * The common shape. The external id is {@code <venue key>:<event id>}; acts come from the site's
     * fields when it has them, otherwise from the title (read as a sentence about this venue). A gig
     * whose acts cannot be found at all is still listed under its title as the act, so it is not lost;
     * the matcher then compares titles.
     */
    public static SourceGig toSourceGig(VenueSite site, Listing listing) {
        TitleParser.Parsed parsed =
                TitleParser.parse(listing.title(), site.venue().name());
        List<String> names = listing.acts().isEmpty() ? parsed.acts() : listing.acts();
        if (names.isEmpty()) {
            names = List.of(listing.title().strip());
        }
        List<SourceArtist> acts = new ArrayList<>();
        for (String name : names) {
            acts.add(new SourceArtist("", name.strip(), null, List.of(), null));
        }
        String status = listing.cancelled() || parsed.flags().contains(TitleParser.Flag.CANCELLED)
                ? "CANCELLED"
                : parsed.flags().contains(TitleParser.Flag.POSTPONED)
                        ? "POSTPONED"
                        : parsed.flags().contains(TitleParser.Flag.RESCHEDULED) ? "RESCHEDULED" : "SCHEDULED";
        VenueSite.Place place = site.venue();
        return new SourceGig(
                SOURCE,
                site.key() + ":" + listing.eventId(),
                listing.url(),
                listing.title().strip(),
                listing.start().atZone(UK).toInstant(),
                null,
                status,
                listing.ticketUrl() != null ? listing.ticketUrl() : listing.url(),
                listing.priceMin(),
                listing.priceMax(),
                listing.priceMin() == null ? null : "GBP",
                // Facts only. A venue's poster is its (or the artist's) artwork,
                // not licensed to us, so it is never stored, even though the adapters can read it.
                null,
                new SourceVenue(
                        site.key(),
                        place.name(),
                        place.address(),
                        place.city(),
                        place.postcode(),
                        place.lat(),
                        place.lng(),
                        UK.getId()),
                acts,
                listing.precision());
    }

    /** "£8 + fees" → [8, 8]; "£12 – £12.5" → [12, 12.5]; "Free" → [0, 0]; nothing readable → null. */
    public static BigDecimal @Nullable [] prices(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.strip().matches("(?i)free.*")) {
            return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO};
        }
        // "Doors 7.15 £12 on the door": when a pound sign is there, only amounts after one are prices.
        Matcher m = (text.contains("£") ? POUNDS : MONEY).matcher(text);
        BigDecimal min = null;
        BigDecimal max = null;
        while (m.find()) {
            BigDecimal value = new BigDecimal(m.group(1));
            min = min == null || value.compareTo(min) < 0 ? value : min;
            max = max == null || value.compareTo(max) > 0 ? value : max;
        }
        return min == null ? null : new BigDecimal[] {min, max};
    }

    /** "Saturday 10th October &#8211; Kholla" → "Saturday 10th October – Kholla". */
    public static String unescape(@Nullable String html) {
        return html == null ? "" : Parser.unescapeEntities(html, false).strip();
    }

    /**
     * The date a day and month without a year mean on a listings page: the next such day from a few
     * days ago (a page lists what is coming; "3 Jan" seen in October is next year's).
     */
    public static java.time.LocalDate nextOccurrence(java.time.MonthDay day, java.time.LocalDate today) {
        java.time.LocalDate candidate = day.atYear(today.getYear());
        return candidate.isBefore(today.minusDays(7)) ? day.atYear(today.getYear() + 1) : candidate;
    }

    /** A URL only when it is http(s). */
    public static @Nullable String webUrl(@Nullable String url) {
        return url != null && url.matches("(?i)https?://.+") ? url : null;
    }
}
