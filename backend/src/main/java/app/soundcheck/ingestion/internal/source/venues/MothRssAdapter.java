package app.soundcheck.ingestion.internal.source.venues;

import app.soundcheck.ingestion.internal.source.SourceIssue;
import app.soundcheck.ingestion.internal.source.TimePrecision;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

/**
 * MOTH Club, Hackney: an RSS feed ({@code /rss}, robots.txt asks for 2 s between requests) with one item
 * per event. The listings page itself is a DICE widget, so this feed is the venue's own data. The feed
 * holds only the most recently added events (10 on 2025-06-05), so daily runs build the full list up
 * over time. Each item:
 * <ul>
 *   <li>title: the event name ending with the date, "Femininomenon Fri 27 Nov 2026";
 *   <li>description, when filled: "&lt;headliner&gt; at MOTH Club, Hackney, London, Fri 27 Nov 2026.
 *       &lt;genres&gt;. Doors 7pm. With A, B. &lt;address&gt;. Tickets on DICE".
 * </ul>
 * The headliner comes from the description's "… at MOTH Club" and the support acts from its "With …"
 * sentence (act fields first); without a description the title (minus the date) is parsed.
 */
@Component
public class MothRssAdapter implements VenueAdapter {

    static final Pattern TITLE_DATE = Pattern.compile(
            "\\s*\\b(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\w*\\s+(\\d{1,2})\\s+([A-Za-z]{3})\\w*\\s+(\\d{4})\\s*$");
    static final Pattern DOORS =
            Pattern.compile("\\bDoors\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?\\b", Pattern.CASE_INSENSITIVE);
    /** "With A, B." — the sentence ends at a full stop followed by a space ("Leather.head" keeps its dot). */
    static final Pattern WITH = Pattern.compile("\\bWith\\s+(.+?)\\.(?:\\s|$)");

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);

    @Override
    public String kind() {
        return "moth-rss";
    }

    @Override
    public Page read(VenueSite site, String body, LocalDate today) {
        List<Listing> listings = new ArrayList<>();
        List<SourceIssue> issues = new ArrayList<>();
        Document feed = Jsoup.parse(body, "", Parser.xmlParser());
        for (Element item : feed.select("item")) {
            String rawTitle = Listings.unescape(
                    item.selectFirst("title") == null
                            ? ""
                            : item.selectFirst("title").text());
            String link = item.selectFirst("link") == null
                    ? null
                    : item.selectFirst("link").text().strip();
            // Descriptions are escaped twice in the feed ("&#38;amp;"): unescape once more.
            String description = Listings.unescape(
                    item.selectFirst("description") == null
                            ? ""
                            : item.selectFirst("description").text());
            Matcher date = TITLE_DATE.matcher(rawTitle);
            if (!date.find()) {
                issues.add(new SourceIssue("NO_DATE", site.key() + ":" + link, rawTitle));
                continue;
            }
            LocalDate day;
            try {
                day = LocalDate.parse(date.group(1) + " " + capital(date.group(2)) + " " + date.group(3), DAY);
            } catch (DateTimeParseException e) {
                issues.add(new SourceIssue("NO_DATE", site.key() + ":" + link, rawTitle));
                continue;
            }
            String title = rawTitle.substring(0, date.start()).strip();
            Matcher doors = DOORS.matcher(description);
            LocalDateTime start;
            TimePrecision precision;
            if (doors.find()) {
                start = day.atTime(time(doors));
                precision = TimePrecision.DOORS;
            } else {
                start = day.atTime(19, 30);
                precision = TimePrecision.DATE_ONLY;
            }
            listings.add(new Listing(
                    eventId(link, rawTitle),
                    Listings.webUrl(link),
                    title,
                    start,
                    precision,
                    acts(description, site.venue().name()),
                    null,
                    null,
                    null,
                    null,
                    false));
        }
        return new Page(listings, issues, null);
    }

    /**
     * The acts in a description: the part before " at MOTH Club" is the headliner, unless it names an
     * event night ("2b2c: Left of The Dial warmup"), in which case only the acts after its "w/" count;
     * then the "With A, B." sentence. Empty when the description is.
     */
    static List<String> acts(String description, String venueName) {
        List<String> acts = new ArrayList<>();
        int at = description.toLowerCase(Locale.ROOT).indexOf(" at " + venueName.toLowerCase(Locale.ROOT));
        if (at <= 0) {
            return acts;
        }
        String head = description.substring(0, at).strip();
        Matcher guests = Pattern.compile("(?i)\\s+w/\\s*").matcher(head);
        String event = head;
        if (guests.find()) {
            event = head.substring(0, guests.start());
            addNames(acts, head.substring(guests.end()));
        }
        if (!event.contains(":") && !event.isBlank()) {
            acts.addFirst(event.strip());
        }
        Matcher with = WITH.matcher(description);
        if (with.find()) {
            addNames(acts, with.group(1));
        }
        return acts;
    }

    /** "A, B & more" → A, B (commas separate acts; "&" stays inside names; "more" is not an act). */
    private static void addNames(List<String> acts, String list) {
        for (String part : list.split("\\s*,\\s*")) {
            String name = part.replaceFirst("(?i)\\s*(?:&|and)\\s*more$", "").strip();
            if (!name.isEmpty() && !name.equalsIgnoreCase("more") && !acts.contains(name)) {
                acts.add(name);
            }
        }
    }

    private static LocalTime time(Matcher doors) {
        int hour = Integer.parseInt(doors.group(1));
        int minute = doors.group(2) == null ? 0 : Integer.parseInt(doors.group(2));
        String half = doors.group(3);
        if (half != null && half.equalsIgnoreCase("pm") && hour < 12) {
            hour += 12;
        } else if (half != null && half.equalsIgnoreCase("am") && hour == 12) {
            hour = 0;
        }
        return LocalTime.of(hour % 24, minute);
    }

    /** The event page's path is MOTH's own id for the event. */
    static String eventId(String link, String title) {
        if (link != null && link.contains("/")) {
            return link.substring(link.lastIndexOf('/') + 1);
        }
        return title.replaceAll("\\W+", "-");
    }

    private static String capital(String month) {
        return month.substring(0, 1).toUpperCase(Locale.ROOT)
                + month.substring(1).toLowerCase(Locale.ROOT);
    }
}
