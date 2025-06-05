package app.soundcheck.ingestion.internal.source.venues;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.resolve.TitleParser;
import app.soundcheck.ingestion.internal.source.CallBudget;
import app.soundcheck.ingestion.internal.source.GigSource;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceIssue;
import app.soundcheck.ingestion.internal.source.SourcePage;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Gigs from venues' own websites, one {@link GigSource} named {@code VENUE} for all of them, so
 * runs, budgets, entity resolution and run stats are Ticketmaster's. Only in live mode, only when
 * {@code soundcheck.ingestion.venues.enabled}, and only the venues listed in {@code ...venues.sites}.
 *
 * <p>Per venue: the venue is pinned (linked once to Ticketmaster's venue when it has one), pages are
 * fetched through the {@link PoliteFetcher} (robots.txt, spacing, back-off), read by the venue's
 * adapter, and turned into {@code SourceGig}s. A refusal or a broken page becomes an issue in the run,
 * never an exception: one venue's trouble does not stop the others. The stored payload is a summary
 * (which URL, what answer, how many events), not the venue's page.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE) // after Ticketmaster: venue listings then match the gigs it created that run
@ConditionalOnExpression(
        "'${soundcheck.ingestion.mode:fixtures}' == 'live' and ${soundcheck.ingestion.venues.enabled:false}")
public class VenueSiteSource implements GigSource {

    private static final Logger log = LoggerFactory.getLogger(VenueSiteSource.class);
    static final int MAX_PAGES_PER_VENUE = 10;
    private static final String TICKETMASTER = "TICKETMASTER";

    private final VenueRegistry registry;
    private final Map<String, VenueAdapter> adapters;
    private final PoliteFetcher fetcher;
    private final CatalogIngest catalog;
    private final IngestionProperties.Venues config;
    private final JsonMapper json;
    private final Clock clock;

    public VenueSiteSource(
            VenueRegistry registry,
            List<VenueAdapter> adapters,
            PoliteFetcher fetcher,
            CatalogIngest catalog,
            IngestionProperties properties,
            JsonMapper json,
            Clock clock) {
        this.registry = registry;
        this.adapters = adapters.stream().collect(Collectors.toMap(VenueAdapter::kind, Function.identity()));
        this.fetcher = fetcher;
        this.catalog = catalog;
        this.config = properties.venues();
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String name() {
        return Listings.SOURCE;
    }

    @Override
    public Stream<SourcePage> pages(Instant from, Instant to, CallBudget budget) {
        return registry.all().stream()
                .filter(site -> config.sites().contains(site.key()))
                .flatMap(site -> pagesOf(site, from, to, budget).stream());
    }

    private List<SourcePage> pagesOf(VenueSite site, Instant from, Instant to, CallBudget budget) {
        VenueAdapter adapter = adapters.get(site.adapter());
        if (adapter == null) {
            return List.of(issueOnly(site, null, "VENUE_NO_ADAPTER", "no adapter called " + site.adapter()));
        }
        pin(site);
        LocalDate today = LocalDate.now(clock.withZone(Listings.UK));
        List<SourcePage> pages = new ArrayList<>();
        URI next = adapter.firstPage(site, today);
        for (int n = 0; next != null && n < MAX_PAGES_PER_VENUE; n++) {
            if (!budget.allowsAnotherCall()) {
                break;
            }
            budget.record();
            PoliteFetcher.Result result = fetcher.get(next);
            if (result.outcome() != PoliteFetcher.Outcome.OK || result.body() == null) {
                pages.add(issueOnly(site, next, "VENUE_" + result.outcome(), result.detail()));
                break;
            }
            VenueAdapter.Page page;
            try {
                page = adapter.read(site, result.body(), today);
            } catch (RuntimeException e) {
                pages.add(issueOnly(
                        site, next, "VENUE_UNREADABLE", e.getClass().getSimpleName() + ": " + e.getMessage()));
                break;
            }
            List<SourceGig> gigs = new ArrayList<>();
            for (Listing listing : page.listings()) {
                // Not music, or moved to another venue (which lists it itself).
                if (site.skips(listing.title())
                        || TitleParser.parse(listing.title()).flags().contains(TitleParser.Flag.MOVED)) {
                    continue;
                }
                SourceGig gig = Listings.toSourceGig(site, listing);
                if (!gig.startsAt().isBefore(from) && gig.startsAt().isBefore(to)) {
                    gigs.add(gig);
                }
            }
            List<SourceIssue> issues = new ArrayList<>(page.issues());
            if (n == 0 && page.listings().isEmpty()) {
                // A site that lists nothing has usually changed its layout: worth a look.
                issues.add(new SourceIssue("VENUE_EMPTY", site.key(), "no events read from " + next));
            }
            pages.add(new SourcePage(
                    request(site, next), summary(site, next, page.listings().size(), gigs.size()), gigs, issues));
            next = page.next();
        }
        log.info("Venue {}: {} page(s) read", site.key(), pages.size());
        return pages;
    }

    /**
     * Links the venue's key to Ticketmaster's venue once, so both sources share one catalog venue, and
     * Ticketmaster's other rows for the building to it as siblings (every run: idempotent, and a row
     * Ticketmaster adds later is picked up).
     */
    private void pin(VenueSite site) {
        if (site.ticketmasterVenueId() == null) {
            return;
        }
        Optional<UUID> pinned = catalog.venueByExternalId(Listings.SOURCE, site.key());
        if (pinned.isEmpty()) {
            pinned = catalog.venueByExternalId(TICKETMASTER, site.ticketmasterVenueId());
            pinned.ifPresent(venueId -> catalog.linkVenue(Listings.SOURCE, site.key(), venueId));
        }
        pinned.ifPresent(venueId -> {
            for (String other : site.otherTicketmasterVenueIds()) {
                catalog.venueByExternalId(TICKETMASTER, other)
                        .ifPresent(sibling -> catalog.linkSiblings(venueId, sibling));
            }
        });
    }

    private SourcePage issueOnly(VenueSite site, URI url, String kind, String detail) {
        return new SourcePage(
                request(site, url),
                summary(site, url, 0, 0),
                List.of(),
                List.of(new SourceIssue(kind, site.key(), detail)));
    }

    private static String request(VenueSite site, URI url) {
        return site.key() + " " + (url == null ? "-" : url);
    }

    private ObjectNode summary(VenueSite site, URI url, int read, int kept) {
        ObjectNode node = json.createObjectNode();
        node.put("venue", site.key());
        node.put("url", url == null ? null : url.toString());
        node.put("eventsRead", read);
        node.put("eventsInWindow", kept);
        return node;
    }
}
