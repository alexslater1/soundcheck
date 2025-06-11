package app.soundcheck.ingestion.internal.source.venues;

import app.soundcheck.ingestion.internal.source.SourceIssue;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Hope & Anchor, Islington: a documented, public, keyless JSON API ({@code /api/v1/events}, OpenAPI at
 * {@code /openapi.json}) whose robots.txt invites its use. Each event has a date, a doors time, a price
 * text, a ticket link, a poster and a {@code bands} list (empty on every event on 2025-06-05, so the
 * title is the usual source of acts). {@code status=all} includes cancelled events, so a cancellation
 * reaches the gig. Pages by {@code pagination.next}.
 */
@Component
public class HopeAndAnchorAdapter implements VenueAdapter {

    private final JsonMapper json;

    public HopeAndAnchorAdapter(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String kind() {
        return "hope-and-anchor-api";
    }

    @Override
    public URI firstPage(VenueSite site, LocalDate today) {
        return URI.create(site.url() + "?status=all&limit=100&from=" + today);
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
        for (JsonNode event : root.path("data")) {
            String id = event.path("id").asText("");
            String title = Listings.unescape(event.path("title").asText(""));
            LocalDateTime start;
            try {
                LocalDate date = LocalDate.parse(event.path("date").asText(""));
                String doors = event.path("doors_time").asText("");
                start = date.atTime(doors.isBlank() ? LocalTime.of(19, 30) : LocalTime.parse(doors));
            } catch (DateTimeParseException e) {
                issues.add(new SourceIssue("NO_DATE", site.key() + ":" + id, title));
                continue;
            }
            List<String> acts = new ArrayList<>();
            for (JsonNode band : event.path("bands")) {
                String name =
                        band.isTextual() ? band.asText("") : band.path("name").asText("");
                if (!name.isBlank()) {
                    acts.add(name.strip());
                }
            }
            BigDecimal[] price = Listings.prices(event.path("price").asText(null));
            listings.add(new Listing(
                    id,
                    Listings.webUrl(event.path("web_url").asText(null)),
                    title,
                    start,
                    site.precision(),
                    acts,
                    Listings.webUrl(event.path("ticket_url").asText(null)),
                    price == null ? null : price[0],
                    price == null ? null : price[1],
                    Listings.webUrl(event.path("poster_url").asText(null)),
                    "cancelled".equalsIgnoreCase(event.path("status").asText(""))));
        }
        String next = root.path("pagination").path("next").asText(null);
        return new Page(listings, issues, next == null || next.isBlank() ? null : URI.create(next));
    }
}
