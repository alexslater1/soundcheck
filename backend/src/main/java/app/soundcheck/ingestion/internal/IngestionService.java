package app.soundcheck.ingestion.internal;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.ingestion.internal.enrich.ArtistEnricher;
import app.soundcheck.ingestion.internal.enrich.PayloadSink;
import app.soundcheck.ingestion.internal.resolve.EntityResolver;
import app.soundcheck.ingestion.internal.source.CallBudget;
import app.soundcheck.ingestion.internal.source.GigSource;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceIssue;
import app.soundcheck.ingestion.internal.source.SourcePage;
import app.soundcheck.shared.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One ingestion run, start to finish: open an {@code ingestion_run} row → pull pages from the
 * source (each raw page kept in {@code raw_payload}) → resolve every gig into the catalog → enrich new
 * artists → flag gigs the source stopped listing → close the row with stats, an issues sample and a status:
 * {@code SUCCEEDED}, {@code PARTIAL} (call budget spent) or {@code FAILED} (the source failed; what was
 * ingested before that stays).
 *
 * <p>Deliberately not {@code @Transactional}: a run takes minutes. Each gig is its own transaction
 * ({@link EntityResolver}), and the bookkeeping commits separately ({@link IngestionRuns}).
 */
@Service
public class IngestionService {

    /** Mapper issues that mean "an event was fetched but could not become a gig". */
    private static final Set<String> SKIPPED_KINDS = Set.of("NO_ARTISTS", "NO_DATE", "NO_VENUE", "NO_ID_OR_NAME");
    /** Rough bounding box of the UK (incl. Northern Ireland and Shetland) for the data-quality check. */
    private static final double UK_SOUTH = 49.8, UK_NORTH = 60.9, UK_WEST = -8.7, UK_EAST = 1.8;

    private static final int NOT_SEEN_SAMPLE = 50;
    private static final Duration HEARTBEAT_EVERY = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    /** The outcome of a run, as logged and returned to callers. */
    public record RunResult(UUID runId, String source, String status, Map<String, Object> stats) {}

    private final List<GigSource> sources;
    private final IngestionRuns runs;
    private final EntityResolver resolver;
    private final ArtistEnricher enricher;
    private final CatalogIngest catalog;
    private final IngestionProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    public IngestionService(
            List<GigSource> sources,
            IngestionRuns runs,
            EntityResolver resolver,
            ArtistEnricher enricher,
            CatalogIngest catalog,
            IngestionProperties properties,
            MeterRegistry meters,
            Clock clock) {
        this.sources = sources;
        this.runs = runs;
        this.resolver = resolver;
        this.enricher = enricher;
        this.catalog = catalog;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    /** The names of the configured sources (one today: Ticketmaster, live or replayed from fixtures). */
    public List<String> sourceNames() {
        return sources.stream().map(GigSource::name).toList();
    }

    /**
     * @param trigger {@code SCHEDULE}, {@code CONSOLE}, {@code CLI} or {@code TEST}
     * @throws ApiException 404 {@code SOURCE_UNKNOWN}; 409 {@code INGESTION_ALREADY_RUNNING}
     */
    public RunResult run(String sourceName, String trigger) {
        GigSource source = sources.stream()
                .filter(s -> s.name().equals(sourceName))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("SOURCE_UNKNOWN", "No gig source named " + sourceName));
        Instant startedAt = Instant.now(clock);
        UUID runId = runs.start(sourceName, trigger, startedAt);
        RunStats stats = new RunStats();
        CallBudget budget = new CallBudget(properties.callBudget());
        Heartbeat heartbeat = new Heartbeat(runId);
        String status = "FAILED";
        String error = "aborted before the run could finish";
        try {
            fetchAndResolve(source, runId, startedAt, budget, stats, heartbeat);
            enrich(runId, stats, heartbeat);
            if (budget.exhausted()) {
                status = "PARTIAL";
                stats.issue("BUDGET_EXHAUSTED", null, "stopped after " + budget.used() + " API calls");
            } else {
                status = "SUCCEEDED";
                flagGigsNoLongerListed(sourceName, runId, stats);
            }
            // Per group, not per run: a group read in full counts even when the run as a whole stopped early.
            unlistGigsDroppedByTheirSite(sourceName, runId, stats);
            error = null;
        } catch (RuntimeException e) {
            // The source gave up (after its retries) or something unexpected broke: keep what was ingested.
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("Ingestion run {} for {} failed: {}", runId, sourceName, error);
        } finally {
            // Also reached for an Error (e.g. out of memory): the row must not be left RUNNING, which
            // would block the next trigger until the heartbeat expires.
            stats.apiCalls = budget.used();
            runs.finish(runId, sourceName, status, stats, error, Instant.now(clock));
            meters.counter("soundcheck.ingestion.runs", "source", sourceName, "status", status)
                    .increment();
            log.info("Ingestion run {} for {} finished {}: {}", runId, sourceName, status, stats.toMap());
        }
        return new RunResult(runId, sourceName, status, stats.toMap());
    }

    /**
     * Enrichment is best-effort: the gigs are already in the catalog, so nothing that goes wrong here may
     * turn the run into FAILED. Artists it could not enrich are picked up by the next run.
     */
    private void enrich(UUID runId, RunStats stats, Heartbeat heartbeat) {
        PayloadSink sink = (from, request, body) -> runs.savePayload(runId, from, request, body, Instant.now(clock));
        try {
            stats.enrichment =
                    enricher.enrichPending(stats, sink, heartbeat::beat).toMap();
        } catch (RuntimeException e) {
            stats.issue("ENRICHMENT_ABORTED", null, e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Enrichment stopped early in run {}: {}", runId, e.toString());
        }
    }

    /** Updates the run's heartbeat at most every 30 seconds. */
    private final class Heartbeat {
        private final UUID runId;
        private Instant last = Instant.EPOCH;

        Heartbeat(UUID runId) {
            this.runId = runId;
        }

        void beat() {
            Instant now = Instant.now(clock);
            if (Duration.between(last, now).compareTo(HEARTBEAT_EVERY) >= 0) {
                runs.heartbeat(runId, now);
                last = now;
            }
        }
    }

    private void fetchAndResolve(
            GigSource source, UUID runId, Instant startedAt, CallBudget budget, RunStats stats, Heartbeat heartbeat) {
        Instant from = startedAt.truncatedTo(ChronoUnit.DAYS);
        Instant to = from.plus(Duration.ofDays(properties.horizonDays()));
        Set<String> seenExternalIds = new HashSet<>();
        try (Stream<SourcePage> pages = source.pages(from, to, budget)) {
            for (Iterator<SourcePage> it = pages.iterator(); it.hasNext(); ) {
                SourcePage page = it.next();
                stats.pages++;
                if (page.readInFull() != null) {
                    stats.groupsReadInFull.add(page.readInFull());
                }
                runs.savePayload(runId, source.name(), page.request(), page.body(), Instant.now(clock));
                for (SourceIssue issue : page.issues()) {
                    stats.issue(issue);
                    if (SKIPPED_KINDS.contains(issue.kind())) {
                        stats.fetched++;
                        stats.skipped++;
                    }
                }
                for (SourceGig gig : page.gigs()) {
                    stats.fetched++;
                    if (!seenExternalIds.add(gig.externalId())) {
                        stats.skipped++;
                        stats.issue("DUPLICATE_EXTERNAL_ID", gig.externalId(), gig.title());
                        continue;
                    }
                    qualityChecks(gig, startedAt, stats);
                    heartbeat.beat();
                    try {
                        // A scratch tally, merged only after the gig's transaction committed: a gig that
                        // rolls back must not leave "created" counts behind.
                        RunStats delta = new RunStats();
                        resolver.ingest(gig, delta, startedAt);
                        stats.merge(delta);
                    } catch (RuntimeException e) {
                        stats.errors++;
                        stats.issue(
                                "INGEST_ERROR", gig.externalId(), e.getClass().getSimpleName() + ": " + e.getMessage());
                        log.warn("Could not ingest {} gig {}: {}", gig.source(), gig.externalId(), e.toString());
                    }
                }
            }
        }
    }

    /** Data-quality issues; the gig is still ingested. */
    private static void qualityChecks(SourceGig gig, Instant now, RunStats stats) {
        Double lat = gig.venue().lat();
        Double lng = gig.venue().lng();
        if (lat == null || lng == null) {
            stats.issue("MISSING_COORDINATES", gig.externalId(), gig.venue().name());
        } else if (lat < UK_SOUTH || lat > UK_NORTH || lng < UK_WEST || lng > UK_EAST) {
            stats.issue("VENUE_OUTSIDE_UK", gig.externalId(), gig.venue().name() + " (" + lat + ", " + lng + ")");
        }
        if (gig.startsAt().isBefore(now)) {
            stats.issue("START_IN_PAST", gig.externalId(), gig.title() + " at " + gig.startsAt());
        }
    }

    /**
     * A future gig the source has not listed for two consecutive complete runs is flagged, not
     * deleted. "Two runs" = it was last seen before the previous successful run started, and this run
     * (which just completed) did not see it either.
     */
    /**
     * An upcoming gig whose only listings come from one group of this source (a venue's own site),
     * and which that group's last two reads in full both left out, is marked UNLISTED: renamed and vanished
     * listings stop showing as gigs. "Two reads": its listing was last seen before the group's previous read
     * in full, and this run's read in full did not see it either.
     */
    private void unlistGigsDroppedByTheirSite(String source, UUID runId, RunStats stats) {
        Instant now = Instant.now(clock);
        for (String group : stats.groupsReadInFull) {
            Optional<Instant> previous = runs.previousReadInFull(source, group, runId);
            if (previous.isPresent()) {
                stats.unlisted += catalog.unlistNotSeenSince(source, group + ":", previous.get(), now);
            }
        }
    }

    private void flagGigsNoLongerListed(String source, UUID runId, RunStats stats) {
        Optional<Instant> previous = runs.previousSuccessStart(source, runId);
        if (previous.isEmpty()) {
            return;
        }
        for (String externalId :
                catalog.futureGigsNotSeenSince(source, previous.get(), Instant.now(clock), NOT_SEEN_SAMPLE)) {
            stats.issue("NOT_SEEN", externalId, "not listed in the last two runs");
        }
    }
}
