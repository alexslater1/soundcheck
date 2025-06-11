package app.soundcheck.ingestion.internal.source.venues;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.catalog.ListingOverlap;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The keep-or-drop rule for venue sites, measured from the catalog after a run: for each venue read from its own
 * site, how many of its shows in the next 60 days Ticketmaster also lists. A site that adds at least
 * {@value #THRESHOLD} shows Ticketmaster misses earns its adapter; one that adds fewer is a candidate to
 * drop. The missed titles are listed too, because a "miss" can also be a matching failure (the same show
 * twice), so the list is what a person checks.
 */
@Component
public class VenueCoverage {

    static final int THRESHOLD = 2;
    static final Duration WINDOW = Duration.ofDays(60);
    private static final String TICKETMASTER = "TICKETMASTER";

    /** One venue's numbers. {@code onTicketmaster}: the registry pins it to a Ticketmaster venue. */
    public record Row(
            String venueKey,
            String venueName,
            boolean onTicketmaster,
            int listed,
            int alsoOnTicketmaster,
            List<ListingOverlap> missed) {

        public int missedCount() {
            return listed - alsoOnTicketmaster;
        }

        public boolean earnsItsPlace() {
            return missedCount() >= THRESHOLD;
        }
    }

    private final CatalogIngest catalog;
    private final VenueRegistry registry;
    private final Clock clock;

    public VenueCoverage(CatalogIngest catalog, VenueRegistry registry, Clock clock) {
        this.catalog = catalog;
        this.registry = registry;
        this.clock = clock;
    }

    public List<Row> measure() {
        Instant now = clock.instant();
        Map<String, List<ListingOverlap>> byVenue = new LinkedHashMap<>();
        for (VenueSite site : registry.all()) {
            byVenue.put(site.key(), new ArrayList<>());
        }
        for (ListingOverlap listing :
                catalog.listingsAlsoListedBy(Listings.SOURCE, TICKETMASTER, now, now.plus(WINDOW))) {
            // The VENUE source's ids are "<venue key>:<the site's event id>".
            String key = listing.externalId()
                    .substring(0, Math.max(0, listing.externalId().indexOf(':')));
            byVenue.computeIfAbsent(key, k -> new ArrayList<>()).add(listing);
        }
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, List<ListingOverlap>> entry : byVenue.entrySet()) {
            VenueSite site = registry.all().stream()
                    .filter(s -> s.key().equals(entry.getKey()))
                    .findFirst()
                    .orElse(null);
            // One row per show: two listings joined to one gig count once.
            Map<UUID, ListingOverlap> perGig = new LinkedHashMap<>();
            entry.getValue().forEach(l -> perGig.putIfAbsent(l.gigId(), l));
            List<ListingOverlap> listings = List.copyOf(perGig.values());
            int also =
                    (int) listings.stream().filter(ListingOverlap::alsoListed).count();
            rows.add(new Row(
                    entry.getKey(),
                    site == null ? "(not in the registry)" : site.venue().name(),
                    site != null && site.ticketmasterVenueId() != null,
                    listings.size(),
                    also,
                    listings.stream().filter(l -> !l.alsoListed()).toList()));
        }
        return rows;
    }

    /** The rows as a Markdown table, then each venue's missed shows. */
    public static String report(List<Row> rows, Instant measuredAt) {
        StringBuilder out = new StringBuilder();
        out.append("Venue coverage, next ")
                .append(WINDOW.toDays())
                .append(" days from ")
                .append(measuredAt)
                .append(" (keep an adapter when its site adds >= ")
                .append(THRESHOLD)
                .append(" shows Ticketmaster misses)\n\n");
        out.append(
                "| venue | on Ticketmaster | own listings | also on Ticketmaster | missed by Ticketmaster | verdict |\n");
        out.append("|---|---|---|---|---|---|\n");
        for (Row row : rows) {
            out.append("| ")
                    .append(row.venueName())
                    .append(" | ")
                    .append(row.onTicketmaster() ? "yes" : "no")
                    .append(" | ")
                    .append(row.listed())
                    .append(" | ")
                    .append(row.alsoOnTicketmaster())
                    .append(" | ")
                    .append(row.missedCount())
                    .append(" | ")
                    .append(row.listed() == 0 ? "nothing read" : row.earnsItsPlace() ? "keep" : "drop?")
                    .append(" |\n");
        }
        for (Row row : rows) {
            if (row.onTicketmaster() && !row.missed().isEmpty()) {
                out.append("\n")
                        .append(row.venueName())
                        .append(", not on Ticketmaster (a miss, or a matching failure):\n");
                for (ListingOverlap missed : row.missed()) {
                    out.append("  - ")
                            .append(LocalDateTime.ofInstant(missed.startsAt(), Listings.UK))
                            .append("  ")
                            .append(missed.title())
                            .append('\n');
                }
            }
        }
        return out.toString();
    }
}
