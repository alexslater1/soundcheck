package app.soundcheck.ingestion.internal.source.ticketmaster;

import app.soundcheck.ingestion.internal.source.SourceArtist;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceIssue;
import app.soundcheck.ingestion.internal.source.SourceVenue;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Maps one Ticketmaster Discovery API {@code events.json} page to {@link SourceGig}s. Pure:
 * no I/O, so it is tested directly against recorded pages.
 */
public final class TicketmasterMapper {

    public static final String SOURCE = "TICKETMASTER";
    /** An event with a date but no time is assumed to start at 19:30 local. */
    static final LocalTime DEFAULT_START = LocalTime.of(19, 30);

    static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/London");
    /** Ticketmaster's placeholders for "no genre". */
    private static final Set<String> NO_GENRE = Set.of("undefined", "other", "miscellaneous");

    private TicketmasterMapper() {}

    /** The gigs of a page plus Ticketmaster's paging block. */
    public record Mapped(List<SourceGig> gigs, List<SourceIssue> issues, int totalElements, int totalPages) {}

    public static Mapped map(JsonNode page) {
        List<SourceGig> gigs = new ArrayList<>();
        List<SourceIssue> issues = new ArrayList<>();
        for (JsonNode event : page.path("_embedded").path("events")) {
            SourceGig gig = mapEvent(event, issues);
            if (gig != null) {
                gigs.add(gig);
            }
        }
        JsonNode paging = page.path("page");
        return new Mapped(
                gigs,
                issues,
                paging.path("totalElements").asInt(0),
                paging.path("totalPages").asInt(0));
    }

    private static @Nullable SourceGig mapEvent(JsonNode event, List<SourceIssue> issues) {
        String id = text(event.path("id"));
        String title = text(event.path("name"));
        if (id == null || title == null) {
            issues.add(new SourceIssue("NO_ID_OR_NAME", id, "event without id or name"));
            return null;
        }
        JsonNode venueNode = event.path("_embedded").path("venues").path(0);
        String venueId = text(venueNode.path("id"));
        String venueName = text(venueNode.path("name"));
        if (venueId == null || venueName == null) {
            issues.add(new SourceIssue("NO_VENUE", id, title));
            return null;
        }
        ZoneId zone = zone(text(event.path("dates").path("timezone")), text(venueNode.path("timezone")));
        Instant startsAt = startsAt(event.path("dates").path("start"), zone);
        if (startsAt == null) {
            issues.add(new SourceIssue("NO_DATE", id, title));
            return null;
        }
        List<SourceArtist> artists = artists(event);
        if (artists.isEmpty()) {
            // The catalog models a gig as a bill of artists; events with no attraction attached are skipped.
            issues.add(new SourceIssue("NO_ARTISTS", id, title));
            return null;
        }
        String url = text(event.path("url"));
        Price price = price(event.path("priceRanges"));
        return new SourceGig(
                SOURCE,
                id,
                url,
                title.strip(),
                startsAt,
                instant(text(event.path("doorsTimes").path("dateTime"))),
                status(text(event.path("dates").path("status").path("code"))),
                url,
                price.min(),
                price.max(),
                price.currency(),
                image(event.path("images")),
                new SourceVenue(
                        venueId,
                        venueName.strip(),
                        text(venueNode.path("address").path("line1")),
                        text(venueNode.path("city").path("name")),
                        text(venueNode.path("postalCode")),
                        coordinate(venueNode.path("location").path("latitude")),
                        coordinate(venueNode.path("location").path("longitude")),
                        zone.getId()),
                artists);
    }

    /**
     * Ticketmaster gives {@code dateTime} (UTC) when the time is known, and only {@code localDate} when it
     * is not ({@code timeTBA} / {@code noSpecificTime}); then the start is 19:30 in the venue's timezone.
     */
    static @Nullable Instant startsAt(JsonNode start, ZoneId zone) {
        String localDate = text(start.path("localDate"));
        if (localDate == null || start.path("dateTBD").asBoolean(false)) {
            return null;
        }
        boolean timeKnown = !start.path("timeTBA").asBoolean(false)
                && !start.path("noSpecificTime").asBoolean(false)
                && text(start.path("localTime")) != null;
        try {
            if (timeKnown) {
                Instant utc = instant(text(start.path("dateTime")));
                if (utc != null) {
                    return utc;
                }
                return LocalDate.parse(localDate)
                        .atTime(LocalTime.parse(text(start.path("localTime"))))
                        .atZone(zone)
                        .toInstant();
            }
            return LocalDate.parse(localDate).atTime(DEFAULT_START).atZone(zone).toInstant();
        } catch (DateTimeException e) {
            return null;
        }
    }

    static String status(@Nullable String code) {
        if (code == null) {
            return "SCHEDULED";
        }
        return switch (code.toLowerCase(Locale.ROOT)) {
            case "cancelled", "canceled" -> "CANCELLED";
            case "postponed" -> "POSTPONED";
            case "rescheduled" -> "RESCHEDULED";
            default -> "SCHEDULED"; // onsale, offsale
        };
    }

    /** Attractions in Ticketmaster's order (headliner first), de-duplicated by id. */
    private static List<SourceArtist> artists(JsonNode event) {
        Map<String, SourceArtist> byId = new LinkedHashMap<>();
        List<String> eventGenres = genres(event.path("classifications"));
        for (JsonNode attraction : event.path("_embedded").path("attractions")) {
            String id = text(attraction.path("id"));
            String name = text(attraction.path("name"));
            if (id == null || name == null) {
                continue;
            }
            List<String> genres = genres(attraction.path("classifications"));
            String mbid = text(
                    attraction.path("externalLinks").path("musicbrainz").path(0).path("id"));
            byId.putIfAbsent(
                    id,
                    new SourceArtist(
                            id,
                            name.strip(),
                            uuid(mbid),
                            genres.isEmpty() ? eventGenres : genres,
                            image(attraction.path("images"))));
        }
        return List.copyOf(byId.values());
    }

    /** {@code genre.name} and {@code subGenre.name} of every classification, without the placeholders. */
    private static List<String> genres(JsonNode classifications) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode c : classifications) {
            for (String field : List.of("genre", "subGenre")) {
                String name = text(c.path(field).path("name"));
                if (name != null && !NO_GENRE.contains(name.toLowerCase(Locale.ROOT))) {
                    names.add(name);
                }
            }
        }
        return List.copyOf(names);
    }

    private record Price(@Nullable BigDecimal min, @Nullable BigDecimal max, @Nullable String currency) {}

    /** Lowest minimum and highest maximum across the price ranges that share the first range's currency. */
    private static Price price(JsonNode ranges) {
        BigDecimal min = null;
        BigDecimal max = null;
        String currency = null;
        for (JsonNode range : ranges) {
            String c = text(range.path("currency"));
            if (c == null || c.length() != 3 || (currency != null && !currency.equals(c))) {
                continue;
            }
            currency = c;
            if (range.path("min").isNumber()) {
                BigDecimal value = money(range.path("min").asDouble());
                min = min == null || value.compareTo(min) < 0 ? value : min;
            }
            if (range.path("max").isNumber()) {
                BigDecimal value = money(range.path("max").asDouble());
                max = max == null || value.compareTo(max) > 0 ? value : max;
            }
        }
        return min == null && max == null ? new Price(null, null, null) : new Price(min, max, currency);
    }

    private static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The smallest 16:9 image at least 640 px wide (card-sized), else the widest there is. Ticketmaster
     * marks generic placeholder artwork with {@code fallback: true}; those are used only as a last resort.
     */
    private static @Nullable String image(JsonNode images) {
        String best = null;
        long bestRank = Long.MAX_VALUE;
        for (JsonNode image : images) {
            String url = text(image.path("url"));
            if (url == null) {
                continue;
            }
            int width = image.path("width").asInt(0);
            boolean cardSized = "16_9".equals(text(image.path("ratio"))) && width >= 640;
            // Lower is better: real artwork before placeholders, card-sized before others, then by width
            // (smallest card-sized image, otherwise the widest).
            long rank = (image.path("fallback").asBoolean(false) ? 2_000_000L : 0L)
                    + (cardSized ? width : 1_000_000L - width);
            if (rank < bestRank) {
                best = url;
                bestRank = rank;
            }
        }
        return best;
    }

    private static ZoneId zone(@Nullable String eventZone, @Nullable String venueZone) {
        for (String candidate : new String[] {eventZone, venueZone}) {
            if (candidate != null) {
                try {
                    return ZoneId.of(candidate);
                } catch (DateTimeException e) {
                    // fall through to the next candidate
                }
            }
        }
        return DEFAULT_ZONE;
    }

    /** Ticketmaster sends coordinates as strings; (0, 0) means "unknown". */
    private static @Nullable Double coordinate(JsonNode node) {
        try {
            double value = node.isNumber() ? node.asDouble() : Double.parseDouble(String.valueOf(text(node)));
            return value == 0.0 ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable Instant instant(@Nullable String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static @Nullable UUID uuid(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static @Nullable String text(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        String value = node.textValue();
        return value == null || value.isBlank() ? null : value;
    }
}
