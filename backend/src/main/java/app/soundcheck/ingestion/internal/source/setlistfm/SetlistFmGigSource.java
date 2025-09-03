package app.soundcheck.ingestion.internal.source.setlistfm;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.catalog.CatalogIngest.BilledArtist;
import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.pastgigs.SetlistFmBudget;
import app.soundcheck.ingestion.internal.pastgigs.SetlistFmClient;
import app.soundcheck.ingestion.internal.source.CallBudget;
import app.soundcheck.ingestion.internal.source.GigSource;
import app.soundcheck.ingestion.internal.source.SourceException;
import app.soundcheck.ingestion.internal.source.SourceIssue;
import app.soundcheck.ingestion.internal.source.SourcePage;
import app.soundcheck.shared.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Past gigs from setlist.fm: for every act on the bill of a
 * gig with clips, the act's UK setlists of the last two years, newest first, page by page, as date-only
 * gigs. The run then resolves them like any listing: a setlist at a gig the catalog has merges into it
 * (adding the act to the bill when missing); one it has not becomes a gig.
 *
 * <p>Within setlist.fm's free key: calls are spaced at one a second (half the key's rate, so the app's live
 * lookups still fit), and a run makes at most {@code backfill.call-budget} of them. An act is marked read
 * only once all its pages are resolved, so a run stopped by the budget resumes with the acts it missed; a
 * later read of an act only goes back to two weeks before the previous one.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE) // after Ticketmaster: setlists then join the gigs it listed
@ConditionalOnExpression(
        "'${soundcheck.ingestion.mode:fixtures}' == 'live' and ${soundcheck.ingestion.setlistfm.backfill.enabled:false}")
public class SetlistFmGigSource implements GigSource {

    private static final Logger log = LoggerFactory.getLogger(SetlistFmGigSource.class);

    private final SetlistFmClient client;
    private final CatalogIngest catalog;
    private final SetlistFmSyncs syncs;
    private final SetlistFmBudget daily;
    private final IngestionProperties.Backfill config;
    private final Bucket pace;
    private final Clock clock;

    public SetlistFmGigSource(
            SetlistFmClient client,
            CatalogIngest catalog,
            SetlistFmSyncs syncs,
            SetlistFmBudget daily,
            IngestionProperties properties,
            Clock clock) {
        this.client = client;
        this.catalog = catalog;
        this.syncs = syncs;
        this.daily = daily;
        this.config = properties.setlistfm().backfill();
        this.pace = Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(1)
                        .refillIntervally(1, Duration.ofNanos(1_000_000_000L / config.requestsPerSecond()))
                        .build())
                .build();
        this.clock = clock;
    }

    @Override
    public String name() {
        return SetlistFmMapper.SOURCE;
    }

    /** The last {@code lookback} (two years) up to today: only gigs that have happened. */
    @Override
    public Window window(Instant today, Duration horizon) {
        return new Window(today.minus(config.lookback()), today);
    }

    @Override
    public Stream<SourcePage> pages(Instant from, Instant to, CallBudget budget) {
        if (!client.configured()) {
            throw new SourceException("SETLISTFM_API_KEY is not set");
        }
        Instant now = clock.instant();
        Map<UUID, Instant> synced = syncs.syncedAt();
        Instant fresh = now.minus(config.resyncAfter());
        // Acts never read first (in name order), then those read longest ago; acts read this week wait.
        List<BilledArtist> due = catalog.artistsOnGigsWithClips().stream()
                .filter(a -> !synced.getOrDefault(a.id(), Instant.EPOCH).isAfter(fresh))
                .sorted(Comparator.comparing(a -> synced.getOrDefault(a.id(), Instant.EPOCH)))
                .toList();
        log.info("setlist.fm backfill: {} act(s) due", due.size());
        Iterator<SourcePage> pages = new PageIterator(
                due,
                synced,
                LocalDate.ofInstant(from, SetlistFmMapper.UK),
                LocalDate.ofInstant(to, SetlistFmMapper.UK),
                budget);
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(pages, Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    /**
     * Lazily, one call per page. An act is marked read when the caller asks for the page after its last
     * one: by then the run has resolved every gig of that last page.
     */
    private final class PageIterator implements Iterator<SourcePage> {

        private final Iterator<BilledArtist> acts;
        private final Map<UUID, Instant> synced;
        private final LocalDate from;
        private final LocalDate to;
        private final CallBudget budget;

        private @Nullable BilledArtist current;
        private int page;
        private int kept;
        private LocalDate cutoff;
        private @Nullable BilledArtist finished;
        private int finishedKept;
        private @Nullable SourcePage next;
        private boolean stopped;

        PageIterator(
                List<BilledArtist> acts, Map<UUID, Instant> synced, LocalDate from, LocalDate to, CallBudget budget) {
            this.acts = acts.iterator();
            this.synced = synced;
            this.from = from;
            this.to = to;
            this.cutoff = from;
            this.budget = budget;
        }

        @Override
        public boolean hasNext() {
            if (next == null) { // the caller has taken (and so resolved) the page before: its act is done
                markFinished();
                while (next == null && !stopped) {
                    advance();
                }
            }
            return next != null;
        }

        @Override
        public SourcePage next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            SourcePage page = next;
            next = null;
            return page;
        }

        private void advance() {
            if (current == null) {
                if (!acts.hasNext()) {
                    stopped = true;
                    return;
                }
                current = acts.next();
                page = 1;
                kept = 0;
                cutoff = cutoffFor(current);
            }
            if (!budget.allowsAnotherCall()) {
                stopped = true; // this act stays unread: the next run starts with it
                return;
            }
            try {
                // The process's daily allowance, shared with the app's live lookups when the run is in the API.
                daily.spend(clock.instant());
            } catch (ApiException e) {
                next = new SourcePage(
                        "(not sent)",
                        JsonNodeFactory.instance.objectNode(),
                        List.of(),
                        List.of(new SourceIssue("SETLISTFM_DAILY_CAP", null, "today's setlist.fm calls are used up")));
                stopped = true;
                return;
            }
            budget.record();
            waitForTurn();
            BilledArtist act = current;
            String request = "search/setlists?"
                    + (act.mbid() != null ? "artistMbid=" + act.mbid() : "artistName=" + act.name())
                    + "&countryCode=GB&p=" + page;
            JsonNode body = client.artistSetlists(act.mbid(), act.name(), page);
            if (body == null) { // setlist.fm answers 404 for "no setlists"
                next = new SourcePage(request, JsonNodeFactory.instance.objectNode(), List.of(), List.of());
                finish();
                return;
            }
            SetlistFmMapper.Page mapped = SetlistFmMapper.map(body, act.mbid(), act.name(), cutoff, to);
            kept += mapped.gigs().size();
            List<SourceIssue> issues = new ArrayList<>(mapped.issues());
            boolean done = mapped.lastPage()
                    || (mapped.oldest() != null && mapped.oldest().isBefore(cutoff));
            if (!done && page >= config.maxPagesPerArtist()) {
                issues.add(new SourceIssue(
                        "SETLISTFM_PAGE_CAP", act.id().toString(), act.name() + ": stopped after " + page + " pages"));
                done = true;
            }
            next = new SourcePage(request, SetlistFmMapper.withoutSongs(body), mapped.gigs(), issues);
            if (done) {
                finish();
            } else {
                page++;
            }
        }

        /** The whole window on an act's first read; after that, from shortly before the previous read. */
        private LocalDate cutoffFor(BilledArtist act) {
            Instant last = synced.get(act.id());
            if (last == null) {
                return from;
            }
            LocalDate since = LocalDate.ofInstant(last.minus(config.overlap()), SetlistFmMapper.UK);
            return since.isAfter(from) ? since : from;
        }

        private void finish() {
            finished = current;
            finishedKept = kept;
            current = null;
        }

        private void markFinished() {
            if (finished != null) {
                syncs.markSynced(finished.id(), clock.instant(), finishedKept);
                finished = null;
            }
        }
    }

    private void waitForTurn() {
        try {
            pace.asBlocking().consume(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("Interrupted while pacing setlist.fm calls");
        }
    }
}
