package app.soundcheck.ingestion.internal.source.venues;

import app.soundcheck.ingestion.internal.source.SourceIssue;
import app.soundcheck.ingestion.internal.source.TimePrecision;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
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
import org.springframework.stereotype.Component;

/**
 * Squarespace venue sites (The Black Heart, The Dome, The Finsbury, Brixton Jamm, The Fiddler's
 * Elbow): the events page's server-rendered HTML. Squarespace's own {@code ?format=json} and
 * {@code ?format=ical} exports are disallowed by these sites' robots.txt, so only the page is read.
 * Two layouts:
 * <ul>
 *   <li>the events collection ({@code article.eventlist-event}): the date in {@code time.event-date
 *       [datetime]}, the start in {@code .event-time-24hr-start}, or the Google Calendar link's UTC
 *       start ({@code dates=20261003T180000Z/…}) when the time element is missing;
 *   <li>summary blocks ({@code .summary-item-record-type-event}): a day and month, sometimes a full
 *       date ({@code time.summary-metadata-item--date}), no start time (date-only).
 * </ul>
 * Titles hold the acts ("Six Strings And A Pulse + Craic Killers + Butterscotch").
 */
@Component
public class SquarespaceAdapter implements VenueAdapter {

    static final Pattern GOOGLE_START = Pattern.compile("dates=(\\d{8}T\\d{6})Z");
    static final DateTimeFormatter GOOGLE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);
    static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.UK);

    @Override
    public String kind() {
        return "squarespace";
    }

    @Override
    public Page read(VenueSite site, String body, LocalDate today) {
        Document page = Jsoup.parse(body, site.url());
        List<Listing> listings = new ArrayList<>();
        List<SourceIssue> issues = new ArrayList<>();
        for (Element event : page.select("article.eventlist-event:not(.eventlist-event--past)")) {
            Element link = event.selectFirst(".eventlist-title a, a.eventlist-title-link");
            if (link == null) {
                continue;
            }
            String title = link.text().strip();
            LocalDateTime start = eventlistStart(event);
            if (start == null) {
                issues.add(new SourceIssue("NO_DATE", site.key(), title));
                continue;
            }
            listings.add(listing(
                    link.absUrl("href"),
                    title,
                    start,
                    hasTime(event) ? site.precision() : TimePrecision.DATE_ONLY,
                    image(event)));
        }
        for (Element event : page.select(".summary-item-record-type-event")) {
            Element link = event.selectFirst("a.summary-title-link");
            if (link == null) {
                continue;
            }
            String title = link.text().strip();
            LocalDate date = summaryDate(event, today);
            if (date == null) {
                issues.add(new SourceIssue("NO_DATE", site.key(), title));
                continue;
            }
            listings.add(
                    listing(link.absUrl("href"), title, date.atTime(19, 30), TimePrecision.DATE_ONLY, image(event)));
        }
        return new Page(listings, issues, null);
    }

    private static Listing listing(
            String url, String title, LocalDateTime start, TimePrecision precision, String image) {
        String path = url.replaceFirst("^https?://[^/]+", "");
        return new Listing(
                path.isEmpty() ? title : path,
                Listings.webUrl(url),
                title,
                start,
                precision,
                List.of(),
                null,
                null,
                null,
                image,
                false);
    }

    /** The UK start of an events-collection entry. */
    static LocalDateTime eventlistStart(Element event) {
        Element date = event.selectFirst("time.event-date[datetime]");
        Element time = event.selectFirst(".event-time-24hr-start, .event-time-24hr time");
        if (date != null) {
            try {
                LocalDate day = LocalDate.parse(date.attr("datetime"));
                if (time != null && time.text().strip().matches("\\d{1,2}:\\d{2}")) {
                    return day.atTime(LocalTime.parse(pad(time.text().strip())));
                }
            } catch (DateTimeParseException e) {
                // fall through to the calendar link
            }
        }
        Element google = event.selectFirst("a.eventlist-meta-export-google[href]");
        if (google != null) {
            Matcher m = GOOGLE_START.matcher(google.attr("href"));
            if (m.find()) {
                Instant utc = LocalDateTime.parse(m.group(1), GOOGLE).toInstant(java.time.ZoneOffset.UTC);
                return LocalDateTime.ofInstant(utc, Listings.UK);
            }
        }
        if (date != null) {
            try {
                return LocalDate.parse(date.attr("datetime")).atTime(19, 30);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }

    /** Whether the entry gives a start time (a 24-hour time or the calendar link's). */
    static boolean hasTime(Element event) {
        Element time = event.selectFirst(".event-time-24hr-start, .event-time-24hr time");
        return (time != null && time.text().strip().matches("\\d{1,2}:\\d{2}"))
                || event.selectFirst("a.eventlist-meta-export-google[href*=dates=]") != null;
    }

    /** "1 October 2026", or the date box's "Oct" + "3" with the year inferred. */
    static LocalDate summaryDate(Element event, LocalDate today) {
        Element full = event.selectFirst("time.summary-metadata-item--date");
        if (full != null) {
            try {
                return LocalDate.parse(full.text().strip(), LONG_DATE);
            } catch (DateTimeParseException e) {
                // fall back to the date box
            }
        }
        Element month = event.selectFirst(".summary-thumbnail-event-date-month");
        Element day = event.selectFirst(".summary-thumbnail-event-date-day");
        if (month == null || day == null) {
            return null;
        }
        try {
            MonthDay md = MonthDay.parse(month.text().strip() + " " + day.text().strip(), MONTH_DAY);
            return Listings.nextOccurrence(md, today);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String image(Element event) {
        Element img = event.selectFirst("img[data-image], img[data-src]");
        if (img == null) {
            return null;
        }
        return Listings.webUrl(img.hasAttr("data-image") ? img.attr("data-image") : img.attr("data-src"));
    }

    private static String pad(String time) {
        return time.length() == 4 ? "0" + time : time;
    }
}
