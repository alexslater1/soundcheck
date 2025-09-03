package app.soundcheck.ingestion.internal.source.setlistfm;

import app.soundcheck.catalog.NameNormaliser;
import app.soundcheck.ingestion.internal.source.SourceArtist;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceIssue;
import app.soundcheck.ingestion.internal.source.SourceVenue;
import app.soundcheck.ingestion.internal.source.TimePrecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One page of setlist.fm's {@code search/setlists} as gig listings. A setlist is one act's set
 * at a venue on a day; it becomes a date-only gig with that one act. Two acts' setlists of the same night
 * meet in the resolver (same source, same instant, same venue: one gig, the second act added to its bill).
 * The songs are never read: the backfill keeps that a gig happened, not what was played.
 */
public final class SetlistFmMapper {

    public static final String SOURCE = "SETLISTFM";

    static final ZoneId UK = ZoneId.of("Europe/London");
    /** A date-only gig starts at 20:00 UK time, as a fan-added one does. */
    static final LocalTime DEFAULT_START = LocalTime.of(20, 0);

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    /** MusicBrainz's "Various Artists": setlist.fm's placeholder for a night of many acts, not an act. */
    static final UUID VARIOUS_ARTISTS = UUID.fromString("89ad4ac3-39f7-470e-963a-56509c546377");

    private SetlistFmMapper() {}

    /**
     * @param gigs the setlists dated in {@code [from, to)} that are this act's
     * @param oldest the earliest date on the page (null when the page held none): paging stops once it
     *     is before the window
     * @param lastPage true when setlist.fm has no further page
     */
    public record Page(List<SourceGig> gigs, List<SourceIssue> issues, @Nullable LocalDate oldest, boolean lastPage) {}

    /**
     * @param actMbid the act asked about, when asked by MusicBrainz id: setlists of any other id are dropped
     * @param actName the act asked about, when asked by name: setlists by any other name are dropped (a
     *     search by name also returns similar names, "Pale Kite" for "Pale Kites")
     */
    public static Page map(JsonNode body, @Nullable UUID actMbid, String actName, LocalDate from, LocalDate to) {
        String wanted = NameNormaliser.normalise(actName);
        List<SourceGig> gigs = new ArrayList<>();
        List<SourceIssue> issues = new ArrayList<>();
        LocalDate oldest = null;
        int count = 0;
        for (JsonNode s : body.path("setlist")) {
            count++;
            String id = s.path("id").asText("");
            LocalDate date;
            try {
                date = LocalDate.parse(s.path("eventDate").asText(""), DATE);
            } catch (DateTimeParseException e) {
                issues.add(new SourceIssue("NO_DATE", id, "eventDate " + s.path("eventDate")));
                continue;
            }
            if (oldest == null || date.isBefore(oldest)) {
                oldest = date;
            }
            if (date.isBefore(from) || !date.isBefore(to)) {
                continue; // outside the window: older than the cutoff, or today and later (not happened yet)
            }
            JsonNode artist = s.path("artist");
            String artistName = artist.path("name").asText("").strip();
            UUID mbid = uuidOrNull(artist.path("mbid").asText(null));
            boolean theAct = actMbid != null
                    ? actMbid.equals(mbid)
                    : NameNormaliser.normalise(artistName).equals(wanted);
            if (!theAct || VARIOUS_ARTISTS.equals(mbid)) {
                continue;
            }
            JsonNode venue = s.path("venue");
            String venueId = venue.path("id").asText("");
            String venueName = venue.path("name").asText("").strip();
            if (id.isEmpty() || venueId.isEmpty() || venueName.isEmpty()) {
                issues.add(new SourceIssue("NO_VENUE", id, artistName + " on " + date));
                continue;
            }
            JsonNode city = venue.path("city");
            JsonNode coords = city.path("coords");
            gigs.add(new SourceGig(
                    SOURCE,
                    id,
                    blankToNull(s.path("url").asText(null)),
                    artistName,
                    date.atTime(DEFAULT_START).atZone(UK).toInstant(),
                    null,
                    "SCHEDULED",
                    null,
                    null,
                    null,
                    null,
                    null,
                    // setlist.fm places a venue only by its city: the city's centre stands in.
                    new SourceVenue(
                            venueId,
                            venueName,
                            null,
                            blankToNull(city.path("name").asText(null)),
                            null,
                            coords.has("lat") ? coords.path("lat").asDouble() : null,
                            coords.has("long") ? coords.path("long").asDouble() : null,
                            UK.getId()),
                    // No external id: the MBID is setlist.fm's only id for an act, and it resolves by MBID.
                    List.of(new SourceArtist("", artistName, mbid, List.of(), null)),
                    TimePrecision.DATE_ONLY));
        }
        int page = body.path("page").asInt(1);
        int perPage = body.path("itemsPerPage").asInt(20);
        int total = body.path("total").asInt(count);
        boolean lastPage = count == 0 || (long) page * perPage >= total;
        return new Page(gigs, issues, oldest, lastPage);
    }

    /**
     * The page as it is kept in {@code ingestion.raw_payload}: everything but the songs ({@code sets}),
     * which the backfill never stores.
     */
    public static JsonNode withoutSongs(JsonNode body) {
        JsonNode copy = body.deepCopy();
        for (JsonNode s : copy.path("setlist")) {
            if (s instanceof ObjectNode setlist) {
                setlist.remove("sets");
                setlist.remove("info");
            }
        }
        return copy;
    }

    private static @Nullable UUID uuidOrNull(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
