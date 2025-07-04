package app.soundcheck.ingestion.internal.source.venues;

import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.source.TimePrecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * The venues whose sites can be read, from {@code venues/london.json}: one entry per venue that
 * passed the compliance gate, with its adapter, address, coordinates and, when Ticketmaster lists it,
 * Ticketmaster's venue id. Which of them are actually read is {@code soundcheck.ingestion.venues.sites}.
 */
@Component
public class VenueRegistry {

    /**
     * A venue whose own listings are deliberately not read: a ticketing widget, a list copied
     * from elsewhere, or no readable page. {@code postcodePrefix} pins a common name to one place.
     */
    public record NotRead(String name, List<String> aliases, @Nullable String postcodePrefix, String reason) {}

    private final List<VenueSite> sites;
    private final List<NotRead> notRead;

    @Autowired
    public VenueRegistry(IngestionProperties properties, ResourceLoader resources, JsonMapper json) {
        try (InputStream in =
                resources.getResource(properties.venues().registry()).getInputStream()) {
            JsonNode root = json.readTree(in);
            this.sites = parse(root);
            this.notRead = parseNotRead(root);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot read the venue registry " + properties.venues().registry(), e);
        }
    }

    /** For tests: a registry of the given sites. */
    VenueRegistry(List<VenueSite> sites) {
        this(sites, List.of());
    }

    /** For tests: a registry of the given sites and venues not read. */
    VenueRegistry(List<VenueSite> sites, List<NotRead> notRead) {
        this.sites = List.copyOf(sites);
        this.notRead = List.copyOf(notRead);
    }

    static List<NotRead> parseNotRead(JsonNode root) {
        List<NotRead> out = new ArrayList<>();
        for (JsonNode v : root.path("notRead")) {
            out.add(new NotRead(
                    v.path("name").asText(""),
                    v.path("aliases")
                            .valueStream()
                            .map(value -> value.asText(""))
                            .toList(),
                    v.path("postcodePrefix").asText(null),
                    v.path("reason").asText("")));
        }
        return List.copyOf(out);
    }

    static List<VenueSite> parse(JsonNode root) {
        List<VenueSite> out = new ArrayList<>();
        for (JsonNode v : root.path("venues")) {
            JsonNode place = v.path("venue");
            out.add(new VenueSite(
                    v.path("key").asText(""),
                    v.path("adapter").asText(""),
                    v.path("url").asText(""),
                    new VenueSite.Place(
                            place.path("name").asText(""),
                            place.path("address").asText(""),
                            place.path("city").asText(""),
                            place.path("postcode").asText(""),
                            place.path("lat").asDouble(),
                            place.path("lng").asDouble()),
                    v.path("ticketmasterVenueId").asText(null),
                    TimePrecision.valueOf(v.path("precision").asText("DOORS")),
                    v.path("notes").asText(null),
                    v.path("skipTitles").asText(null),
                    v.path("otherTicketmasterVenueIds")
                            .valueStream()
                            .map(value -> value.asText(""))
                            .toList(),
                    selectors(v.path("selectors"))));
        }
        return List.copyOf(out);
    }

    private static VenueSite.@Nullable Selectors selectors(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return new VenueSite.Selectors(
                node.path("item").asText(""),
                node.path("title").asText(""),
                node.path("link").asText(null),
                node.path("date").asText(""),
                node.path("dateFormats")
                        .valueStream()
                        .map(value -> value.asText(""))
                        .toList(),
                node.path("time").asText(null),
                node.path("price").asText(null),
                node.path("ticket").asText(null),
                node.path("image").asText(null),
                node.path("titleLines").asBoolean(false),
                node.path("next").asText(null));
    }

    public List<VenueSite> all() {
        return sites;
    }

    /** The venues whose own listings are not read, with why. */
    public List<NotRead> notRead() {
        return notRead;
    }
}
