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
        /** "[SHOW MOVED]": the gig happens at another venue, which lists it itself. */
        MOVED,
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
            marker("show\\s+moved|venue\\s+change[ds]?|new\\s+venue|moved\\s+venue", Flag.MOVED),
            marker("early\\s+show", Flag.EARLY_SHOW),
            marker("late\\s+show", Flag.LATE_SHOW),
            marker(
                    "\\d{2}\\s*\\+(?!\\s*[\\p{L}\\d])|over\\s+\\d{2}s?|\\d{2}s?\\s+and\\s+over|ages?\\s+\\d{2}\\s*\\+",
                    Flag.AGE_RESTRICTED),
            marker("free\\s+entry|free\\s+gig|free", Flag.FREE),
            marker(
                    "last\\s+(?:few\\s+)?tickets|low\\s+tickets|final\\s+tickets|few\\s+tickets\\s+left|selling\\s+fast",
                    null),
            marker("on\\s+sale\\s+now|tickets\\s+on\\s+sale|all\\s+welcome", null),
            marker("this\\s+show\\s+is\\s+\\w+", null));

    /** "Club Fandango presents:", "Eat Your Own Ears Present", "Civil War Presents..." at the start. */
    private static final Pattern PROMOTER = Pattern.compile(
            "^(.{2,60}?)\\s+(?:presents?|proudly\\s+presents?)(?:\\.{2,3}|…)?\\s*[:\\-–—]?\\s+",
            Pattern.CASE_INSENSITIVE);

    /** A numbered club night in front: "SHAKE SOME ACTON #178.", "Rockaway Beach #112". */
    private static final Pattern SERIES = Pattern.compile("^(.{2,50}?)\\s+#\\d+\\s*[.:\\-–—]?\\s*");

    /** A numbered night with a dash and then a bill: "We Are So Young 44 – Nico + Shrink". */
    private static final Pattern SERIES_DASH = Pattern.compile(
            "^(.{2,50}?)\\s+(?:vol\\.?\\s*|no\\.?\\s*)?\\d+\\s+[–—-]\\s+(?=.*(?:\\s\\+\\s|,|\\s/\\s))",
            Pattern.CASE_INSENSITIVE);

    /** "Single Release Show", "*Album Launch Show*", "Joint EP Launch Show": the occasion, after the act. */
    private static final Pattern RELEASE = Pattern.compile(
            "\\s*[*(\\[–—-]*\\s*(?:joint\\s+|debut\\s+|new\\s+)?(?:single|ep|album|record|mixtape|vinyl)\\s+"
                    + "(?:release|launch)(?:\\s+(?:show|party|gig|night))?\\s*[*)\\]]*\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** "HEAVY HALLOWEEN ft. A, B": an occasion before "featuring" is not an act. */
    private static final Pattern FEATURING_EVENT = Pattern.compile(
            "\\b(?:halloween|christmas|xmas|festival|fest|all-?dayer|weekender|showcase|takeover|party|fundraiser|benefit)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FEATURING =
            Pattern.compile("^(.+?)\\s+(?:feat\\.?|ft\\.|featuring)\\s+", Pattern.CASE_INSENSITIVE);

    /** "(2PM – 7PM)", "[19:00-23:00]": opening hours, not a name. */
    private static final Pattern TIME_RANGE = Pattern.compile(
            "\\s*[(\\[]\\s*\\d{1,2}(?:[:.]\\d{2})?\\s*(?:am|pm)?\\s*[–—-]\\s*\\d{1,2}(?:[:.]\\d{2})?\\s*(?:am|pm)?\\s*[)\\]]",
            Pattern.CASE_INSENSITIVE);

    /** "An Evening with X": the act is X. */
    private static final Pattern EVENING_WITH =
            Pattern.compile("^(?:an?\\s+)?evening\\s+with\\s+", Pattern.CASE_INSENSITIVE);

    /** "… and guests TBA", "+ special guests": more acts to come, at the end. */
    private static final Pattern GUESTS_TAIL = Pattern.compile(
            "\\s+(?:and|&|\\+|plus|w/|with)\\s+(?:very\\s+)?(?:special\\s+)?guests?(?:\\s+tb[ac])?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** "Subject A supporting Skaville UK": the headliner comes second. */
    private static final Pattern SUPPORTING =
            Pattern.compile("^(.+?)\\s+supporting\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    /** A tour or anniversary named at the end without a separator: "… IGYC Tour London", "… 45th Anniversary Show". */
    private static final Pattern TRAILING_TOUR = Pattern.compile(
            "\\s+((?:\\d+(?:st|nd|rd|th)\\s+anniversary(?:\\s+(?:show|tour|gig|celebration))?)"
                    + "|(?:\\S+\\s+tour(?:\\s+(?:\\d{4}|london|uk|europe))*))\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** On the left of a colon, these mean an event's name, so the acts are on the right. */
    private static final Pattern EVENT_WORDS = Pattern.compile(
            "\\b(?:showcase|presents?|birthday|party|club|night|festival|weekender|all-?dayer|takeover|series|"
                    + "sessions?|fundraiser|benefit|celebrat\\w*|launch|special|ultimate|social|alldayer|"
                    // a studio, label or promoter: "Syncroix Studios: Amelie Jat"
                    + "studios?|records|recordings|collective|promotions|productions|"
                    + "festive|christmas|xmas|halloween)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Separators between acts on one bill. */
    private static final Pattern SPLIT = Pattern.compile(
            // " with " splits in lower case or all capitals only: "Back With a Bang" is a name.
            "\\s+\\+\\s+|\\s*//\\s*|\\s+w/\\s*|(?-i:\\s+(?:with|WITH)\\s+)|\\s+plus\\s+|\\s*\\bsupport(?:ed)?\\s+(?:from|by)\\s*:?\\s*|\\s*\\bsupport\\s*:\\s*"
                    + "|\\s*,\\s*|\\s+(?:feat\\.?|ft\\.|featuring)\\s+|\\s+/\\s+|(?<=[\\p{L}\\d]{3})/(?=[\\p{L}\\d]{3})|\\s+x\\s+(?=[A-Z])"
                    // DJs playing back to back
                    + "|\\s+b2b\\s+",
            Pattern.CASE_INSENSITIVE);

    /** Words that mean "more acts to come", not an act. */
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "^(?:(?:very\\s+)?special\\s+guests?(?:\\s+tb[ac])?|guests?(?:\\s+tb[ac])?|support(?:\\s+(?:acts?|bands?|poets?|djs?))?(?:\\s+tb[ac])?|more(?:\\s+tb[ac])?|tb[ac]|friends"
                    + "|plus\\s+support|plus\\s+guests|and\\s+more|\\.\\.\\.|djs?|resident\\s+djs?|full\\s+band)$",
            Pattern.CASE_INSENSITIVE);

    /** The part after a colon or a spaced dash is a tour or event name when it says so. */
    private static final Pattern TOUR_WORDS = Pattern.compile(
            "\\b(?:tour|live|in\\s+concert|album|ep|single|launch|release|anniversary|headline|show|night|"
                    + "evening|plays|performs|celebrat\\w*|farewell|reunion|residency|session|special|years?|20\\d\\d|"
                    + "club\\s*night|halloween|christmas|xmas|fundraiser|benefit|all-?dayer|weekender|matinee)\\b",
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
        String venue = venuePattern(venueName);
        // "Connor McBride | London, Hope & Anchor": a trailing "| <place>" naming the venue.
        String text = title.replaceAll("(?i)\\s*\\|\\s*(?:london,?\\s*)?(?:the\\s+)?" + venue + "\\b.*$", " ");
        text = text.replaceAll("(?i)(?:\\blive\\s+)?(?:\\bat\\s+|@\\s*)(?:the\\s+)?" + venue + "\\b", " ");
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

    /** The venue's name as a pattern: without a leading "The", "&" and "and" interchangeable. */
    static String venuePattern(String venueName) {
        String bare = venueName.strip().replaceFirst("(?i)^the\\s+", "");
        List<String> parts = new ArrayList<>();
        for (String part : bare.split("\\s*&\\s*|\\s+and\\s+")) {
            parts.add(Pattern.quote(part));
        }
        return String.join("\\s*(?:&|and)\\s*", parts);
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
        // Squarespace's "(Copy)" on a duplicated page; opening hours.
        text = TIME_RANGE.matcher(text.replaceAll("(?i)\\s*\\(copy\\)", " ")).replaceAll(" ");
        text = tidy(text.replaceAll("[(\\[]\\s*[)\\]]", " "));
        // "ZBUKU LIVE IN LONDON!", "BRITALLICA - LIVE IN LONDON": where, not who.
        text = tidy(text.replaceAll("(?i)\\s*[-–—|]?\\s*\\blive\\s+in\\s+(?:london|the\\s+uk|uk)\\b!?", " "));
        // A quoted event name after the act: Lenny Kaye and Friends "RIOT ON OXFORD STREET",
        // CEDRY2K – “Improgramabil” – Featuring Pietonu.
        String quoted = null;
        Matcher quote = Pattern.compile("\\s*[\"“]([^\"”]{3,})[\"”]").matcher(text);
        if (quote.find() && quote.start() > 0) {
            quoted = tidy(quote.group(1));
            text = tidy(text.substring(0, quote.start()) + " " + text.substring(quote.end()))
                    .replaceAll("\\s+[–—-]\\s+[–—-]\\s+", " – ");
        }
        text = text.replaceAll("(?i)\\s+(?:and|&)\\s+friends\\s*$", "");

        // "Pale Kites. Support: Night Bus" — a support line is part of the bill, not a tour name.
        text = text.replaceAll("(?i)[.\\s]*\\bsupport(?:ed\\s+by|\\s+from)?\\s*:\\s*", " + ");

        Matcher promoter = PROMOTER.matcher(text);
        if (promoter.find()) {
            text = text.substring(promoter.end());
        }
        Matcher series = SERIES.matcher(text);
        if (series.find()) {
            text = text.substring(series.end());
        }
        Matcher seriesDash = SERIES_DASH.matcher(text);
        if (seriesDash.find()) {
            text = text.substring(seriesDash.end());
        }
        text = EVENING_WITH.matcher(text).replaceFirst("");
        text = GUESTS_TAIL.matcher(text).replaceFirst("");

        String tour = null;
        // "Promoter: Peri Rae Single Release Show": the act is what the release follows, so after a
        // colon it is on the right.
        boolean releaseAfterColon = false;
        Matcher release = RELEASE.matcher(text);
        if (release.find() && release.start() > 0) {
            tour = tidy(release.group().replaceAll("^[\\s*(\\[–—-]+|[\\s*)\\]]+$", ""));
            String before = text.substring(0, release.start());
            releaseAfterColon = before.matches(".*:\\s+\\S.*");
            text = tidy(before);
        }
        Matcher colon = Pattern.compile(":\\s+").matcher(text);
        if (colon.find() && colon.start() > 0) {
            // "Pale Kites: Autumn Tour" has the acts on the left; "Ultimate Showcase: Pale Kites" and
            // "Birthday Party: A + B" on the right.
            String left = text.substring(0, colon.start());
            String right = text.substring(colon.end());
            boolean actsOnTheRight =
                    releaseAfterColon || EVENT_WORDS.matcher(left).find() || (separates(right) && !separates(left));
            tour = tidy(actsOnTheRight ? left : right);
            text = actsOnTheRight ? right : left;
        } else if (tour == null) {
            int cut = tourCut(text);
            if (cut >= 0) {
                tour = tidy(text.substring(cut).replaceFirst("^\\s*[:|–—-]+\\s*", ""));
                text = text.substring(0, cut);
            }
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

        if (tour == null && quoted != null) {
            tour = quoted;
        }
        Matcher trailing = TRAILING_TOUR.matcher(text);
        if (tour == null && trailing.find() && trailing.start() > 0) {
            tour = tidy(trailing.group(1));
            text = text.substring(0, trailing.start());
        }
        Matcher supporting = SUPPORTING.matcher(tidy(text));
        if (supporting.matches()) {
            text = supporting.group(2) + " + " + supporting.group(1);
        }
        Matcher featuring = FEATURING.matcher(tidy(text));
        if (featuring.find()
                && FEATURING_EVENT.matcher(featuring.group(1)).find()
                && !SPLIT.matcher(featuring.group(1)).find()) {
            tour = tour == null ? tidy(featuring.group(1)) : tour;
            text = tidy(text).substring(featuring.end());
        }

        String whole = tidy(text);
        List<String> acts = new ArrayList<>();
        for (String part : SPLIT.split(whole)) {
            String act = tidy(part.replaceAll("^[(\\[](.*)[)\\]]$", "$1")
                    .replaceAll("(?i)\\s+dj\\s+set$", "")
                    .replaceAll("(?i)\\s+(?:very\\s+)?special\\s+guests?$", "")
                    .replaceAll("(?i)\\s*[(\\[](?:live|dj\\s+set|acoustic|solo|full\\s+band|band)[)\\]]$", ""));
            if (!act.isEmpty() && !PLACEHOLDER.matcher(act).matches() && !acts.contains(act)) {
                acts.add(act);
            }
        }
        return new Parsed(List.copyOf(acts), whole, flags, tour == null || tour.isEmpty() ? null : tour);
    }

    /** Whether a piece of title lists several acts (a bill separator in it). */
    private static boolean separates(String text) {
        return text.matches("(?is).*(?:\\s\\+\\s|,|\\sw/|\\swith\\s|\\s/\\s).*");
    }

    /** Where the tour part starts: at a spaced dash or bar when what follows has a tour word. -1 when none. */
    private static int tourCut(String text) {
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
                // Emoji and symbols around a title ("🎸 BRITALLICA 🎸") are decoration.
                .replaceAll("^[\\p{So}\\p{Sk}\\x{FE0F}\\s]+|[\\p{So}\\p{Sk}\\x{FE0F}\\s]+$", "")
                // A leading "!" goes only when a space follows ("Tickets! Pale Kites"), not in a name ("!!!").
                .replaceAll("^(?:[\\s:|*.,–—-]|!(?=\\s))+|[\\s:|*!.,–—-]+$", "")
                .strip();
    }
}
