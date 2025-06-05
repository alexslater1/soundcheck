package app.soundcheck.ingestion.internal.source.venues;

import app.soundcheck.ingestion.internal.source.SourceIssue;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Squarespace "list" blocks used as a what's-on page (The Ivy House): each block carries its items as
 * JSON in {@code data-current-context}. A dated item's description starts "Fri 2 Oct / 19:00 / £12"
 * (day, time, price); items without such a line are regulars ("Every Monday …") and are skipped, as
 * are non-music titles the registry names ({@code skipTitles}).
 */
@Component
public class SquarespaceListAdapter implements VenueAdapter {

    static final Pattern DATE_LINE = Pattern.compile(
            "^\\s*(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\w*\\s+(\\d{1,2})\\s+([A-Za-z]{3})\\w*\\s*/\\s*(\\d{1,2}[:.]\\d{2})(?:\\s*/\\s*(.+))?");
    static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.UK);

    private final JsonMapper json;

    public SquarespaceListAdapter(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String kind() {
        return "squarespace-list";
    }

    @Override
    public Page read(VenueSite site, String body, LocalDate today) {
        List<Listing> listings = new ArrayList<>();
        List<SourceIssue> issues = new ArrayList<>();
        for (Element block : Jsoup.parse(body, site.url()).select("[data-current-context]")) {
            JsonNode context;
            try {
                context = json.readTree(block.attr("data-current-context"));
            } catch (JacksonException e) {
                continue;
            }
            for (JsonNode item : context.path("userItems")) {
                String title = Listings.unescape(item.path("title").asText(""));
                String firstLine = Jsoup.parse(item.path("description").asText("")).select("p").stream()
                        .map(Element::text)
                        .filter(t -> !t.isBlank())
                        .findFirst()
                        .orElse("");
                Matcher m = DATE_LINE.matcher(firstLine);
                if (title.isBlank() || !m.find()) {
                    continue; // a regular ("Every Monday at 7pm"), not a dated event
                }
                LocalDate day;
                try {
                    day = Listings.nextOccurrence(
                            MonthDay.parse(m.group(1) + " " + capital(m.group(2)), MONTH_DAY), today);
                } catch (DateTimeParseException e) {
                    issues.add(new SourceIssue("NO_DATE", site.key(), title));
                    continue;
                }
                LocalTime time = LocalTime.parse(pad(m.group(3).replace('.', ':')));
                BigDecimal[] price = Listings.prices(m.group(4));
                String button = item.path("button").path("buttonLink").asText(null);
                listings.add(new Listing(
                        title + "@" + day,
                        Listings.webUrl(button),
                        title,
                        day.atTime(time),
                        site.precision(),
                        List.of(),
                        Listings.webUrl(button),
                        price == null ? null : price[0],
                        price == null ? null : price[1],
                        Listings.webUrl(item.path("image").path("assetUrl").asText(null)),
                        false));
            }
        }
        return new Page(listings, issues, null);
    }

    private static String capital(String month) {
        return month.substring(0, 1).toUpperCase(Locale.ROOT)
                + month.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String pad(String time) {
        return time.length() == 4 ? "0" + time : time;
    }
}
