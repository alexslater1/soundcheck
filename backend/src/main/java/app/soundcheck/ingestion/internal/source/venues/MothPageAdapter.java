package app.soundcheck.ingestion.internal.source.venues;

import app.soundcheck.ingestion.internal.source.SourceIssue;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * MOTH Club's own "All Events" page ({@code /All-Events}; robots.txt allows it, Crawl-delay 2), which
 * lists far more than its RSS (the RSS keeps about ten). The site
 * is built with Cargo: one page per show, and the All Events page embeds its first batch of those show
 * pages as JSON in {@code <script type="text/json" data-set="FirstloadThumbnails">} (50 on 2025-07-04):
 * {@code title} ("Femininomenon Fri 27 Nov 2026"), {@code url} (the show page's path) and {@code excerpt},
 * the same text as the RSS description ({@link MothRssAdapter}), so the same parsing and the same event
 * ids apply. Only the venue's own show pages are read: never the DICE data the page's scripts show, and
 * not the rest of the list, which Cargo loads by script as the visitor scrolls.
 */
@Component
public class MothPageAdapter implements VenueAdapter {

    static final String SHOWS = "script[type=text/json][data-set=FirstloadThumbnails]";

    private final JsonMapper json;

    public MothPageAdapter(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String kind() {
        return "moth-page";
    }

    @Override
    public Page read(VenueSite site, String body, LocalDate today) {
        List<Listing> listings = new ArrayList<>();
        List<SourceIssue> issues = new ArrayList<>();
        Document page = Jsoup.parse(body);
        Element shows = page.selectFirst(SHOWS);
        if (shows == null) {
            // The page changed shape: an empty read, reported by the source as VENUE_EMPTY.
            return new Page(listings, issues, null);
        }
        JsonNode items;
        try {
            items = json.readTree(shows.data());
        } catch (JacksonException e) {
            issues.add(new SourceIssue("VENUE_UNREADABLE", site.key(), "show list is not JSON: " + e.getMessage()));
            return new Page(listings, issues, null);
        }
        for (JsonNode item : items) {
            String rawTitle = Listings.unescape(
                    item.path("title_no_html").asText(item.path("title").asText("")));
            String path = item.path("url").asText("");
            String link = path.isBlank()
                    ? null
                    : URI.create(site.url()).resolve("/" + path).toString();
            String description =
                    completeSentences(Listings.unescape(item.path("excerpt").asText("")));
            MothRssAdapter.listing(site, rawTitle, link, description)
                    .ifPresentOrElse(
                            listings::add,
                            () -> issues.add(new SourceIssue("NO_DATE", site.key() + ":" + path, rawTitle)));
        }
        return new Page(listings, issues, null);
    }

    /**
     * An excerpt cut off with "..." (26 of 50 on 2025-07-04) loses its last, unfinished sentence: a cut
     * support list ("With Leather.head,...") would otherwise yield half a name. The doors time and the
     * headliner come earlier and stay.
     */
    static String completeSentences(String excerpt) {
        String text = excerpt.strip();
        if (!text.endsWith("...") && !text.endsWith("…")) {
            return text;
        }
        int end = text.lastIndexOf(". ");
        return end < 0 ? "" : text.substring(0, end + 1);
    }
}
