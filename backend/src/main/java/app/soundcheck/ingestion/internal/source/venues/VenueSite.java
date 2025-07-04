package app.soundcheck.ingestion.internal.source.venues;

import app.soundcheck.ingestion.internal.source.TimePrecision;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One venue whose own website is read, from the registry ({@code venues/london.json}). The venue
 * is pinned: every listing lands on it, never on a fuzzy match. {@code ticketmasterVenueId} links it to
 * the venue Ticketmaster already created, so both sources share one catalog venue.
 *
 * @param adapter which reader understands the site ({@link VenueAdapter#kind()})
 * @param precision what the site's times mean: usually doors
 */
public record VenueSite(
        String key,
        String adapter,
        String url,
        Place venue,
        @Nullable String ticketmasterVenueId,
        TimePrecision precision,
        @Nullable String notes,
        /** Titles matching this (case-insensitive) are not music and are left out: quizzes, karaoke… */
        @Nullable String skipTitles,
        /**
         * Other Ticketmaster venues in the same building (rooms, or the venue listed twice): linked as
         * siblings of the pinned one so the same show is found whichever row Ticketmaster used.
         */
        List<String> otherTicketmasterVenueIds,
        /** Where things are on the page, for the {@code html-selectors} adapter only. */
        @Nullable Selectors selectors) {

    public VenueSite {
        otherTicketmasterVenueIds =
                otherTicketmasterVenueIds == null ? List.of() : List.copyOf(otherTicketmasterVenueIds);
    }

    public VenueSite(
            String key,
            String adapter,
            String url,
            Place venue,
            @Nullable String ticketmasterVenueId,
            TimePrecision precision,
            @Nullable String notes,
            @Nullable String skipTitles,
            List<String> otherTicketmasterVenueIds) {
        this(
                key,
                adapter,
                url,
                venue,
                ticketmasterVenueId,
                precision,
                notes,
                skipTitles,
                otherTicketmasterVenueIds,
                null);
    }

    public VenueSite(
            String key,
            String adapter,
            String url,
            Place venue,
            @Nullable String ticketmasterVenueId,
            TimePrecision precision,
            @Nullable String notes) {
        this(key, adapter, url, venue, ticketmasterVenueId, precision, notes, null, List.of(), null);
    }

    /**
     * Nights at music venues that are not gigs, left out everywhere: no act to resolve, and an "artist"
     * called "Pub Quiz" would be noise in search.
     */
    static final Pattern NOT_MUSIC = Pattern.compile(
            "open\\s*mic|\\bquiz\\b|karaoke|poetry|comedy|stand-?up|life\\s+drawing|workshop|wrestling"
                    + "|book\\s+club|\\bmarket\\b|\\byoga\\b|\\bbingo\\b|film\\s+(?:club|screening|night)"
                    + "|movie\\s+night|rockeoke|tournament|dungeons\\s*(?:&|and)\\s*dragons|vintage\\s+sale|kilo\\s+sale"
                    + "|in\\s+conversation",
            Pattern.CASE_INSENSITIVE);

    /**
     * What makes a night a club or DJ night. "Party" is not one:
     * it would drop "Bloc Party" and album launch parties.
     */
    static final Pattern CLUB_MARKER = Pattern.compile(
            "\\bclub\\s*nights?\\b|(?<!at the )\\bdisco\\b|\\bdjs?\\b|\\bdj\\s*sets?\\b|\\bb2b\\b"
                    + "|\\ball[\\s-]*night[\\s-]*long\\b|\\bday\\s*party\\b|\\brave\\b|\\bclubbing\\b"
                    + "|\\b\\d0[’']?s\\s+club\\b",
            Pattern.CASE_INSENSITIVE);

    /** How a bill is cut into its acts for {@link #clubNight}: "+", commas, "/", "with", "ft." / "featuring". */
    private static final Pattern BILL_SEPARATOR = Pattern.compile(
            "\\s+\\+\\s+|,\\s+|\\s+/\\s+|\\s+with\\s+|\\s+(?:ft|feat)\\.?\\s+|\\s+featuring\\s+",
            Pattern.CASE_INSENSITIVE);

    /** A promoter before a colon ("SILO Presents: …"), not an act. */
    private static final Pattern PRESENTER = Pattern.compile("^.*?\\bpresents?\\s*:\\s*", Pattern.CASE_INSENSITIVE);

    /**
     * A club or DJ-only night: the title has a {@link #CLUB_MARKER} and every act on its bill is one too
     * ("Weird Disco Club Night", "Djrum: All Night Long", "Beats Kebab (DJ set)"). A gig that ends with a DJ
     * stays ("EMOM + Palindrones + DJs", "Leaf Dog + Bva with DJ Jazz T"): one act on the bill is a band.
     */
    static boolean clubNight(String title) {
        if (!CLUB_MARKER.matcher(title).find()) {
            return false;
        }
        String bill = PRESENTER.matcher(title.strip()).replaceFirst("");
        for (String act : BILL_SEPARATOR.split(bill)) {
            if (!act.isBlank() && !CLUB_MARKER.matcher(act).find()) {
                return false;
            }
        }
        return true;
    }

    /** Whether a title is left out: not music, a club or DJ night, or what the venue's entry says to skip. */
    public boolean skips(String title) {
        return NOT_MUSIC.matcher(title).find()
                || clubNight(title)
                || (skipTitles != null
                        && Pattern.compile(skipTitles, Pattern.CASE_INSENSITIVE)
                                .matcher(title)
                                .find());
    }

    /**
     * CSS selectors for a page of repeated event blocks ({@link SelectorAdapter}): {@code item} finds each
     * block; the others are read inside it, {@code "css@attr"} for an attribute. {@code dateFormats} are
     * {@link java.time.format.DateTimeFormatter} patterns (UK English) or {@code "iso"}, tried in order.
     * {@code titleLines}: the acts are on separate lines of the title. {@code next}: where the next page's
     * link is, for a paged list (read from the whole page, followed up to the source's page limit).
     */
    public record Selectors(
            String item,
            String title,
            @Nullable String link,
            String date,
            List<String> dateFormats,
            @Nullable String time,
            @Nullable String price,
            @Nullable String ticket,
            @Nullable String image,
            boolean titleLines,
            /** The next page's link in a paged list, e.g. {@code "a[rel=next]@href"}; null when one page. */
            @Nullable String next) {}

    /** Where the venue is: from its own site, coordinates from postcodes.io. */
    public record Place(String name, String address, String city, String postcode, double lat, double lng) {}
}
