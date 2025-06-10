package app.soundcheck.ingestion.internal.source.venues;

import app.soundcheck.ingestion.internal.source.SourceIssue;
import app.soundcheck.ingestion.internal.source.TimePrecision;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * A venue page whose events are repeated blocks with the title, date and time in fixed places (The
 * Lexington, The Dublin Castle, EartH, Village Underground, The Fighting Cocks, Paper Dress Vintage).
 * Where things are is configuration ({@link VenueSite.Selectors} in the registry), so a venue of this
 * kind needs no code. A selector is a CSS query inside the event block; {@code "css@attr"} reads an
 * attribute instead of the text, and {@code "@attr"} reads it from the block itself.
 *
 * <p>Dates are read with the registry's patterns, tried in order; a pattern without a year means the
 * next such day from today. A time is read from the time element when it holds one ("19:00", "7PM",
 * "Doors 7.15", "18:30 - 22:00"); without one the listing is date-only. A title whose acts are on
 * separate lines ({@code titleLines}) is read as a bill ("A + B + C").
 */
@Component
public class SelectorAdapter implements VenueAdapter {

    /** A clock time: with minutes ("19:00", "7.15") or with am/pm ("7PM"); a bare "12" is a price, not a time. */
    static final Pattern TIME = Pattern.compile("(?i)\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?\\b");

    @Override
    public String kind() {
        return "html-selectors";
    }

    @Override
    public Page read(VenueSite site, String body, LocalDate today) {
        VenueSite.Selectors config = site.selectors();
        if (config == null) {
            return new Page(
                    List.of(),
                    List.of(new SourceIssue("VENUE_UNREADABLE", site.key(), "no selectors configured")),
                    null);
        }
        Document page = Jsoup.parse(body, site.url());
        List<Element> items = page.select(config.item());
        // A link shared by every event (the promoter's home page) does not identify one event.
        Map<String, Integer> linkCounts = new HashMap<>();
        for (Element item : items) {
            String link = value(item, config.link());
            if (link != null) {
                linkCounts.merge(link, 1, Integer::sum);
            }
        }
        List<Listing> listings = new ArrayList<>();
        List<SourceIssue> issues = new ArrayList<>();
        for (Element item : items) {
            String title = title(item, config);
            if (title.isBlank()) {
                continue;
            }
            String dateText = value(item, config.date());
            LocalDate day = dateText == null ? null : date(dateText, config.dateFormats(), today);
            if (day == null) {
                issues.add(new SourceIssue("NO_DATE", site.key(), title + " · " + dateText));
                continue;
            }
            String timeText = config.time() == null ? null : value(item, config.time());
            LocalTime time = timeText == null ? null : time(timeText);
            if (time == null && dateText != null) {
                time = timeInDate(dateText, config.dateFormats());
            }
            String link = value(item, config.link());
            String url = link == null ? null : Listings.webUrl(link);
            boolean ownLink = url != null && linkCounts.getOrDefault(link, 0) == 1;
            String path = ownLink ? url.replaceFirst("^https?://[^/]+", "") : "";
            BigDecimal[] price = config.price() == null ? null : Listings.prices(value(item, config.price()));
            String ticket = config.ticket() == null ? null : Listings.webUrl(value(item, config.ticket()));
            String image = config.image() == null ? null : Listings.webUrl(value(item, config.image()));
            listings.add(new Listing(
                    path.isEmpty() ? title + "@" + day : path,
                    url,
                    title,
                    day.atTime(time == null ? LocalTime.of(19, 30) : time),
                    time == null ? TimePrecision.DATE_ONLY : site.precision(),
                    List.of(),
                    ticket,
                    price == null ? null : price[0],
                    price == null ? null : price[1],
                    image,
                    false));
        }
        return new Page(listings, issues, null);
    }

    /** The title, or the bill when each act is on its own line. */
    static String title(Element item, VenueSite.Selectors config) {
        if (!config.titleLines()) {
            String text = value(item, config.title());
            return text == null ? "" : text.strip();
        }
        Element element = item.selectFirst(config.title());
        if (element == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (String part : element.html().split("(?i)<br\\s*/?>")) {
            String line = Listings.unescape(Jsoup.parse(part).text()).strip();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return String.join(" + ", lines);
    }

    /** {@code "css"} → its text, {@code "css@attr"} → its attribute (absolute for links), {@code "@attr"} → the item's. */
    static @Nullable String value(Element item, @Nullable String selector) {
        if (selector == null || selector.isBlank()) {
            return null;
        }
        int at = selector.lastIndexOf('@');
        String css = at < 0 ? selector : selector.substring(0, at).strip();
        String attr = at < 0 ? null : selector.substring(at + 1).strip();
        Element element = css.isEmpty() ? item : item.selectFirst(css);
        if (element == null) {
            return null;
        }
        if (attr == null) {
            String text = element.text().strip();
            return text.isEmpty() ? null : text;
        }
        String value = attr.equals("href") || attr.equals("src") ? element.absUrl(attr) : element.attr(attr);
        if (value.isBlank() && attr.equals("style")) {
            return null;
        }
        return value.isBlank() ? null : value.strip();
    }

    /** The day, by the first pattern that reads the text; a pattern without a year means the next such day. */
    static @Nullable LocalDate date(String text, List<String> patterns, LocalDate today) {
        String clean =
                text.strip().replaceAll("(?i)(\\d)(st|nd|rd|th)\\b", "$1").replaceAll("\\s+", " ");
        for (String pattern : patterns) {
            if (pattern.equals("iso")) {
                try {
                    return OffsetDateTime.parse(clean).toLocalDate();
                } catch (DateTimeParseException e) {
                    try {
                        return LocalDate.parse(clean.substring(0, Math.min(10, clean.length())));
                    } catch (DateTimeParseException | StringIndexOutOfBoundsException ignored) {
                        continue;
                    }
                }
            }
            try {
                TemporalAccessor parsed = formatter(pattern).parse(clean);
                MonthDay day = MonthDay.from(parsed);
                if (parsed.isSupported(ChronoField.YEAR)) {
                    return day.atYear(parsed.get(ChronoField.YEAR));
                }
                return Listings.nextOccurrence(day, today);
            } catch (DateTimeParseException ignored) {
                // try the next pattern
            }
        }
        return null;
    }

    /** The time a date pattern carries itself ("Fri October 02, 19:00"), if any. */
    static @Nullable LocalTime timeInDate(String text, List<String> patterns) {
        for (String pattern : patterns) {
            if (!pattern.contains("H") && !pattern.contains("h")) {
                continue;
            }
            try {
                return LocalTime.from(formatter(pattern).parse(text.strip().replaceAll("\\s+", " ")));
            } catch (DateTimeParseException ignored) {
                // try the next pattern
            }
        }
        return null;
    }

    /**
     * The first clock time in the text: "19:00", "7PM", "Doors 7.15" (an evening gig: 7.15 is 19:15),
     * "18:30 - 22:00" (the start). Null when the text has none.
     */
    static @Nullable LocalTime time(String text) {
        Matcher m = TIME.matcher(text);
        while (m.find()) {
            if (m.group(2) == null && m.group(3) == null) {
                continue; // "£12": a number, not a time
            }
            int hour = Integer.parseInt(m.group(1));
            int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            String half = m.group(3) == null ? null : m.group(3).toLowerCase(Locale.ROOT);
            if (hour > 23 || minute > 59) {
                continue;
            }
            if ("pm".equals(half) && hour < 12) {
                hour += 12;
            } else if ("am".equals(half) && hour == 12) {
                hour = 0;
            } else if (half == null && hour >= 1 && hour <= 11) {
                hour += 12; // venues write evening doors as "7.15"
            }
            return LocalTime.of(hour, minute);
        }
        return null;
    }

    private static DateTimeFormatter formatter(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .parseLenient()
                .appendPattern(pattern)
                .toFormatter(Locale.UK);
    }
}
