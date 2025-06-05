package app.soundcheck.ingestion.internal.resolve;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads the acts out of a gig title, for sources that give no separate act fields. Venue
 * websites pack everything into one line: {@code "SOLD OUT: Club Fandango presents Pale Kites + Night
 * Bus + support (18+)"}. The parser removes status words, flags, promoter prefixes and tour names, then
 * splits the rest into acts on the separators venues use for a bill ({@code +}, {@code w/},
 * {@code with}, {@code support from}, commas, {@code feat.}).
 *
 * <p>It never splits on {@code &} or {@code and}: they are far more often part of a name ("Florence +
 * The Machine" is the opposite trap, so {@link Parsed#whole} keeps the unsplit text and the resolver
 * tries it as one known artist first). Pure and table-tested; when a source has act fields, they are
 * used instead and this is not called.
 */
public final class TitleParser {

    private TitleParser() {}

    /** Things a title says about the show rather than about who plays. */
    public enum Flag {
        SOLD_OUT,
        CANCELLED,
        POSTPONED,
        RESCHEDULED,
        EARLY_SHOW,
        LATE_SHOW,
        AGE_RESTRICTED,
        FREE
    }

    /**
     * @param acts the acts in billing order, headliner first
     * @param whole the act part before splitting (for "is this one known artist?")
     * @param tour the tour or event name, when the title had one
     */
    public record Parsed(List<String> acts, String whole, Set<Flag> flags, @Nullable String tour) {}

    private record Marker(Pattern pattern, @Nullable Flag flag) {}

    /** A marker starts the title or follows a space, bracket or separator (not consumed, so brackets pair up). */
    private static final String OPEN = "(?<![^\\s(\\[*|:–—-])";

    private static final String CLOSE = "(?=$|[\\s)\\]*!|:–—,.-])";

    private static Marker marker(String words, @Nullable Flag flag) {
        return new Marker(Pattern.compile(OPEN + "(?:" + words + ")" + CLOSE, Pattern.CASE_INSENSITIVE), flag);
    }

    /** Removed wherever they appear; most also raise a flag. */
    private static final List<Marker> MARKERS = List.of(
            marker("sold\\s*out", Flag.SOLD_OUT),
            marker("cancell?ed", Flag.CANCELLED),
            marker("postponed", Flag.POSTPONED),
            marker("rescheduled|new\\s+date|date\\s+change|moved\\s+to\\s+\\w+", Flag.RESCHEDULED),
            marker("early\\s+show", Flag.EARLY_SHOW),
            marker("late\\s+show", Flag.LATE_SHOW),
            marker(
                    "\\d{2}\\s*\\+|over\\s+\\d{2}s?|\\d{2}s?\\s+and\\s+over|ages?\\s+\\d{2}\\s*\\+",
                    Flag.AGE_RESTRICTED),
            marker("free\\s+entry|free\\s+gig|free", Flag.FREE),
            marker(
                    "last\\s+(?:few\\s+)?tickets|low\\s+tickets|final\\s+tickets|few\\s+tickets\\s+left|selling\\s+fast",
                    null),
            marker("on\\s+sale\\s+now|tickets\\s+on\\s+sale", null),
            marker("this\\s+show\\s+is\\s+\\w+", null));

    /** "Club Fandango presents:", "Eat Your Own Ears Present" at the start. */
    private static final Pattern PROMOTER = Pattern.compile(
            "^(.{2,60}?)\\s+(?:presents?|proudly\\s+presents?)\\s*[:\\-–—]?\\s+", Pattern.CASE_INSENSITIVE);

    /** Separators between acts on one bill. */
    private static final Pattern SPLIT = Pattern.compile(
            "\\s+\\+\\s+|\\s+w/\\s*|\\s+with\\s+|\\s*\\bsupport(?:ed)?\\s+(?:from|by)\\s*:?\\s*|\\s*\\bsupport\\s*:\\s*"
                    + "|\\s*,\\s*|\\s+(?:feat\\.?|ft\\.|featuring)\\s+|\\s+/\\s+|\\s+x\\s+(?=[A-Z])",
            Pattern.CASE_INSENSITIVE);

    /** Words that mean "more acts to come", not an act. */
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "^(?:(?:very\\s+)?special\\s+guests?(?:\\s+tb[ac])?|guests?(?:\\s+tb[ac])?|support(?:\\s+acts?)?(?:\\s+tb[ac])?|more(?:\\s+tb[ac])?|tb[ac]|friends"
                    + "|plus\\s+support|plus\\s+guests|and\\s+more|\\.\\.\\.|djs?|resident\\s+djs?|full\\s+band)$",
            Pattern.CASE_INSENSITIVE);

    /** The part after a colon or a spaced dash is a tour or event name when it says so. */
    private static final Pattern TOUR_WORDS = Pattern.compile(
            "\\b(?:tour|live|in\\s+concert|album|ep|single|launch|release|anniversary|headline|show|night|"
                    + "evening|plays|performs|celebrat\\w*|farewell|reunion|residency|session|special|20\\d\\d)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final String WEEKDAY =
            "(?:mon|tue|tues|wed|weds|thu|thur|thurs|fri|sat|sun)(?:day|nesday|sday|urday)?\\.?";
    private static final String MONTH = "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?";
    /** "Saturday 10th October", "Fri 27 Nov 2026", "24th October 2026", with an optional "on" in front. */
    private static final Pattern DATE_PHRASE = Pattern.compile(
            "(?:\\bon\\s+)?(?:\\b" + WEEKDAY + "\\s+)?\\b\\d{1,2}(?:st|nd|rd|th)?\\s+" + MONTH + "(?:\\s+\\d{4})?\\b",
            Pattern.CASE_INSENSITIVE);
    /** "are headlining", "headlines", "Headlining …" around the act's name. */
    private static final Pattern HEADLINING =
            Pattern.compile("(?:\\b(?:is|are)\\s+)?\\bheadlin(?:e|es|ing)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Like {@link #parse(String)} for a title written as a sentence about the venue ("The Ondines are
     * headlining at The Amersham Arms on Saturday 24th October 2026!"): the venue's name, date phrases
     * and "headlining" wording are removed first. "Headlining Rough Edges – S0FTD0LLY" keeps what
     * follows the dash: the event name comes first there.
     */
    public static Parsed parse(String title, String venueName) {
        String venue = Pattern.quote(venueName.replaceFirst("(?i)^the\\s+", ""));
        String text = title.replaceAll("(?i)(?:\\blive\\s+)?(?:\\bat\\s+|@\\s*)(?:the\\s+)?" + venue + "\\b", " ");
        text = DATE_PHRASE.matcher(text).replaceAll(" ");
        boolean leadsWithHeadlining = title.strip().toLowerCase(Locale.ROOT).startsWith("headlin");
        text = HEADLINING.matcher(text).replaceAll(" ").strip();
        Matcher afterDash = Pattern.compile("^(.+?)\\s+[–—-]\\s+(.+)$").matcher(text);
        if (leadsWithHeadlining && afterDash.matches()) {
            text = afterDash.group(2);
        }
        text = text.replaceAll("^\\s*[–—-]\\s*|\\s*[–—-]\\s*$", " ");
        return parse(text);
    }

    public static Parsed parse(String title) {
        Set<Flag> flags = EnumSet.noneOf(Flag.class);
        String text = title.replace(' ', ' ').strip().replaceAll("\\s+", " ");

        for (Marker marker : MARKERS) {
            Matcher m = marker.pattern().matcher(text);
            if (m.find()) {
                if (marker.flag() != null) {
                    flags.add(marker.flag());
                }
                text = marker.pattern().matcher(text).replaceAll(" ");
            }
        }
        text = tidy(text.replaceAll("[(\\[]\\s*[)\\]]", " "));

        // "Pale Kites. Support: Night Bus" — a support line is part of the bill, not a tour name.
        text = text.replaceAll("(?i)[.\\s]*\\bsupport(?:ed\\s+by|\\s+from)?\\s*:\\s*", " + ");

        Matcher promoter = PROMOTER.matcher(text);
        if (promoter.find()) {
            text = text.substring(promoter.end());
        }

        String tour = null;
        int cut = tourCut(text);
        if (cut >= 0) {
            tour = tidy(text.substring(cut).replaceFirst("^\\s*[:|–—-]+\\s*", ""));
            text = text.substring(0, cut);
        }
        // A trailing "(…)" that is not an act: "Pale Kites (Album Launch)", "Night Bus (Acoustic)".
        Matcher bracket = Pattern.compile("\\s*[(\\[]([^)\\]]*)[)\\]]\\s*$").matcher(text);
        if (bracket.find()
                && (TOUR_WORDS.matcher(bracket.group(1)).find()
                        || bracket.group(1).length() > 40
                        || bracket.group(1).toLowerCase(Locale.ROOT).matches("acoustic|solo|dj set|live|full band"))) {
            tour = tour == null ? tidy(bracket.group(1)) : tour;
            text = text.substring(0, bracket.start());
        }

        String whole = tidy(text);
        List<String> acts = new ArrayList<>();
        for (String part : SPLIT.split(whole)) {
            String act = tidy(part.replaceAll("^[(\\[](.*)[)\\]]$", "$1"));
            if (!act.isEmpty() && !PLACEHOLDER.matcher(act).matches() && !acts.contains(act)) {
                acts.add(act);
            }
        }
        return new Parsed(List.copyOf(acts), whole, flags, tour == null || tour.isEmpty() ? null : tour);
    }

    /**
     * Where the tour part starts: at {@code ": "} unless the left side is short and the right side reads
     * like more acts, or at a spaced dash / bar when the right side contains a tour word. -1 when none.
     */
    private static int tourCut(String text) {
        Matcher colon = Pattern.compile(":\\s+").matcher(text);
        if (colon.find() && colon.start() > 0) {
            return colon.start();
        }
        Matcher dash = Pattern.compile("\\s+[–—|-]\\s+").matcher(text);
        while (dash.find()) {
            if (TOUR_WORDS.matcher(text.substring(dash.end())).find()) {
                return dash.start();
            }
        }
        return -1;
    }

    private static String tidy(String s) {
        return s.replaceAll("\\s+", " ")
                // A leading "!" goes only when a space follows ("Tickets! Pale Kites"), not in a name ("!!!").
                .replaceAll("^(?:[\\s:|*.,–—-]|!(?=\\s))+|[\\s:|*!.,–—-]+$", "")
                .strip();
    }
}
