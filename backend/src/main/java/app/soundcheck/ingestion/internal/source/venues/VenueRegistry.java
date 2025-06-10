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

    private final List<VenueSite> sites;

    @Autowired
    public VenueRegistry(IngestionProperties properties, ResourceLoader resources, JsonMapper json) {
        try (InputStream in =
                resources.getResource(properties.venues().registry()).getInputStream()) {
            this.sites = parse(json.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot read the venue registry " + properties.venues().registry(), e);
        }
    }

    /** For tests: a registry of the given sites. */
    VenueRegistry(List<VenueSite> sites) {
        this.sites = List.copyOf(sites);
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
                node.path("titleLines").asBoolean(false));
    }

    public List<VenueSite> all() {
        return sites;
    }
}
