package app.soundcheck.ingestion.internal.source.venues;

import app.soundcheck.ingestion.internal.source.SourceIssue;
import app.soundcheck.ingestion.internal.source.TimePrecision;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Venue pages that describe their events with schema.org JSON-LD ({@code <script
 * type="application/ld+json">}): {@code Event} / {@code MusicEvent} objects, alone, in arrays, in an
 * {@code @graph} or in an {@code ItemList}. Acts come from {@code performer} (an object or a list of
 * them) when present, otherwise from the title. {@code startDate} with an offset is converted to UK
 * time; without one it is read as UK time. Events dated before yesterday are ignored: some sites carry
 * stale template JSON-LD (The Garage's describes a 2019 festival), which this keeps out.
 */
@Component
public class JsonLdAdapter implements VenueAdapter {

    static final Set<String> EVENT_TYPES =
            Set.of("Event", "MusicEvent", "TheaterEvent", "ComedyEvent", "SocialEvent", "Festival");

    private final JsonMapper json;

    public JsonLdAdapter(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String kind() {
        return "json-ld";
    }

    @Override
    public Page read(VenueSite site, String body, LocalDate today) {
        List<Listing> listings = new ArrayList<>();
        List<SourceIssue> issues = new ArrayList<>();
        for (Element script : Jsoup.parse(body).select("script[type=application/ld+json]")) {
            JsonNode root;
            try {
                root = json.readTree(script.data());
            } catch (JacksonException e) {
                issues.add(new SourceIssue("VENUE_UNREADABLE", site.key(), "a JSON-LD block is not JSON"));
                continue;
            }
            collect(root, site, today, listings, issues);
        }
        return new Page(listings, issues, null);
    }

    private void collect(JsonNode node, VenueSite site, LocalDate today, List<Listing> out, List<SourceIssue> issues) {
        if (node.isArray()) {
            node.forEach(child -> collect(child, site, today, out, issues));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if (node.has("@graph")) {
            collect(node.path("@graph"), site, today, out, issues);
        }
        if (node.has("itemListElement")) {
            for (JsonNode element : node.path("itemListElement")) {
                collect(element.has("item") ? element.path("item") : element, site, today, out, issues);
            }
        }
        if (!isEvent(node)) {
            return;
        }
        String title = Listings.unescape(node.path("name").asText(""));
        LocalDateTime start = when(node.path("startDate").asText(""));
        if (start == null) {
            issues.add(new SourceIssue("NO_DATE", site.key(), title));
            return;
        }
        if (start.toLocalDate().isBefore(today.minusDays(1))) {
            return; // stale template data or a past event
        }
        LocalDateTime doors = when(node.path("doorTime").asText(""));
        List<String> acts = new ArrayList<>();
        JsonNode performers = node.path("performer");
        for (JsonNode performer : performers.isArray() ? performers : List.of(performers)) {
            String name = performer.isTextual()
                    ? performer.asText("")
                    : performer.path("name").asText("");
            if (!name.isBlank()) {
                acts.add(Listings.unescape(name));
            }
        }
        JsonNode offers = node.path("offers");
        JsonNode offer = offers.isArray() && !offers.isEmpty() ? offers.get(0) : offers;
        BigDecimal[] price = Listings.prices(
                offer.path("price").asText(offer.path("lowPrice").asText(null)));
        String url = Listings.webUrl(node.path("url").asText(null));
        String status = node.path("eventStatus").asText("");
        out.add(new Listing(
                url != null ? url : title + "@" + start,
                url,
                title,
                doors != null ? doors : start,
                doors != null ? TimePrecision.DOORS : site.precision(),
                acts,
                Listings.webUrl(offer.path("url").asText(null)),
                price == null ? null : price[0],
                price == null ? null : price[1],
                image(node.path("image")),
                status.endsWith("EventCancelled")));
    }

    private static boolean isEvent(JsonNode node) {
        JsonNode type = node.path("@type");
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (EVENT_TYPES.contains(t.asText(""))) {
                    return true;
                }
            }
            return false;
        }
        return EVENT_TYPES.contains(type.asText(""));
    }

    /** "2026-10-16T19:30:00+01:00" → 19:30 UK; "2026-10-16T19:30" → 19:30 UK; "2026-10-16" → 19:30. */
    static LocalDateTime when(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(Listings.UK).toLocalDateTime();
        } catch (DateTimeParseException e) {
            // no offset
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException e) {
            // date only
        }
        try {
            return LocalDate.parse(text).atTime(19, 30);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String image(JsonNode image) {
        if (image.isTextual()) {
            return Listings.webUrl(image.asText(null));
        }
        if (image.isArray() && !image.isEmpty()) {
            return image(image.get(0));
        }
        return Listings.webUrl(image.path("url").asText(null));
    }
}
