package app.soundcheck.ingestion.internal.source.venues;

import app.soundcheck.ingestion.internal.source.SourceIssue;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * WordPress sites running "The Events Calendar" (Amersham Arms, New River Studios): its public REST
 * endpoint {@code /wp-json/tribe/events/v1/events}. {@code start_date} is read as UK wall-clock time
 * whatever the site's timezone label says: both researched sites label London times "UTC+0". Titles
 * carry HTML entities and are often sentences ("X headlines at The Y on Thursday 29th October!"); the
 * site has no act fields (the organiser is the promoter), so acts come from the title. Pages by
 * {@code next_rest_url}.
 */
@Component
public class TribeEventsAdapter implements VenueAdapter {

    static final DateTimeFormatter WALL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JsonMapper json;

    public TribeEventsAdapter(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String kind() {
        return "tribe-events";
    }

    @Override
    public URI firstPage(VenueSite site, LocalDate today) {
        return URI.create(site.url() + "?per_page=50&start_date=" + today);
    }

    @Override
    public Page read(VenueSite site, String body, LocalDate today) {
        List<Listing> listings = new ArrayList<>();
        List<SourceIssue> issues = new ArrayList<>();
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            return new Page(List.of(), List.of(new SourceIssue("VENUE_UNREADABLE", site.key(), "not JSON")), null);
        }
        for (JsonNode event : root.path("events")) {
            String id = event.path("id").asText("");
            String title = Listings.unescape(event.path("title").asText(""));
            LocalDateTime start;
            try {
                start = LocalDateTime.parse(event.path("start_date").asText(""), WALL);
            } catch (DateTimeParseException e) {
                issues.add(new SourceIssue("NO_DATE", site.key() + ":" + id, title));
                continue;
            }
            BigDecimal[] price =
                    Listings.prices(Listings.unescape(event.path("cost").asText(null)));
            JsonNode image = event.path("image");
            listings.add(new Listing(
                    id,
                    Listings.webUrl(event.path("url").asText(null)),
                    title,
                    start,
                    event.path("all_day").asBoolean(false)
                            ? app.soundcheck.ingestion.internal.source.TimePrecision.DATE_ONLY
                            : site.precision(),
                    List.of(),
                    null,
                    price == null ? null : price[0],
                    price == null ? null : price[1],
                    image.isObject() ? Listings.webUrl(image.path("url").asText(null)) : null,
                    "canceled".equalsIgnoreCase(event.path("status").asText(""))));
        }
        String next = root.path("next_rest_url").asText(null);
        return new Page(listings, issues, next == null || next.isBlank() ? null : URI.create(next));
    }
}
