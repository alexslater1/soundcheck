package app.soundcheck.ingestion.internal;

import static app.soundcheck.shared.jooq.ingestion.Tables.INGESTION_RUN;

import app.soundcheck.ingestion.internal.source.setlistfm.SetlistFmMapper;
import app.soundcheck.ingestion.internal.source.ticketmaster.TicketmasterMapper;
import app.soundcheck.ingestion.internal.source.venues.Listings;
import app.soundcheck.shared.jooq.ingestion.tables.records.IngestionRunRecord;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Cursor;
import app.soundcheck.shared.web.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The console's view of ingestion: which sources this deployment runs, the run history with each
 * run's stats and issues, and "Run now", which only queues a run ({@link IngestionQueue} runs it in
 * a scheduler process).
 */
@Service
public class AdminIngestion {

    private static final TypeReference<Map<String, Object>> STATS = new TypeReference<>() {};
    private static final TypeReference<List<Issue>> ISSUES = new TypeReference<>() {};

    /** The sources ingestion has adapters for, in the order the schedule runs them. Skiddle has none yet. */
    static final List<String> KNOWN_SOURCES =
            List.of(TicketmasterMapper.SOURCE, Listings.SOURCE, SetlistFmMapper.SOURCE);

    /** A source and whether this deployment's configuration turns it on. */
    @Schema(name = "IngestionSource")
    public record SourceView(
            String name,
            boolean enabled,
            @Schema(description = "fixtures (recorded, invented gigs) or live (real APIs)") String mode,
            @Schema(nullable = true, description = "The source's latest run, whatever its status")
                    RunSummary lastRun) {}

    /** One run as the table lists it: timings, the main counters, and its issues counted by kind. */
    @Schema(name = "IngestionRun")
    public record RunSummary(
            UUID id,
            String source,
            @Schema(allowableValues = {"SCHEDULE", "CONSOLE", "CLI", "TEST"}) String trigger,
            @Schema(allowableValues = {"QUEUED", "RUNNING", "SUCCEEDED", "PARTIAL", "FAILED"}) String status,
            @Schema(nullable = true, description = "When \"Run now\" queued it; null for runs that started at once")
                    Instant queuedAt,
            @Schema(description = "When it started; for a QUEUED run, when it was queued") Instant startedAt,
            @Schema(nullable = true) Instant finishedAt,
            @Schema(nullable = true, description = "Who pressed \"Run now\"") UUID requestedBy,
            int fetched,
            int created,
            int updated,
            int unchanged,
            int merged,
            int skipped,
            int errors,
            int apiCalls,
            Map<String, Integer> issueCounts,
            @Schema(nullable = true) String error) {}

    /** One stored issue: the sample keeps at most 200 a run ({@link RunStats#MAX_ISSUES}). */
    @Schema(name = "IngestionIssue")
    public record Issue(
            String kind, @Schema(nullable = true) String externalId, @Schema(nullable = true) String detail) {}

    /** A run with every stat and every stored issue. */
    @Schema(name = "IngestionRunDetail")
    public record RunDetail(RunSummary run, Map<String, Object> stats, List<Issue> issues) {}

    private final DSLContext dsl;
    private final IngestionRuns runs;
    private final IngestionProperties properties;
    private final JsonMapper json;
    private final Clock clock;

    public AdminIngestion(
            DSLContext dsl, IngestionRuns runs, IngestionProperties properties, JsonMapper json, Clock clock) {
        this.dsl = dsl;
        this.runs = runs;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Whether this process's configuration turns a source on, read from the same properties the sources'
     * {@code @Conditional…} annotations use, so the API process needs none of the scheduler's beans.
     */
    static boolean enabled(String source, IngestionProperties properties) {
        return switch (source) {
            case TicketmasterMapper.SOURCE -> true; // fixtures replay it offline; live mode calls the API
            case Listings.SOURCE -> properties.live() && properties.venues().enabled();
            case SetlistFmMapper.SOURCE ->
                properties.live() && properties.setlistfm().backfill().enabled();
            default -> false;
        };
    }

    @Transactional(readOnly = true)
    public List<SourceView> sources() {
        String mode = properties.live() ? "live" : "fixtures";
        List<SourceView> views = new ArrayList<>();
        for (String name : KNOWN_SOURCES) {
            // A few runs a day per source: ingestion_run_started serves this.
            RunSummary last = dsl.selectFrom(INGESTION_RUN)
                    .where(INGESTION_RUN.SOURCE.eq(name))
                    .orderBy(INGESTION_RUN.STARTED_AT.desc(), INGESTION_RUN.ID.desc())
                    .limit(1)
                    .fetchOptional()
                    .map(this::summary)
                    .orElse(null);
            views.add(new SourceView(name, enabled(name, properties), mode, last));
        }
        return views;
    }

    /** Newest first, keyset on (started_at, id); expected index: ingestion_run_started (a small table). */
    @Transactional(readOnly = true)
    public Page<RunSummary> list(
            @Nullable String source, @Nullable String status, @Nullable String cursor, @Nullable Integer limit) {
        Cursor after = Cursor.decode(cursor);
        int size = Cursor.limit(limit);
        Condition where = DSL.noCondition();
        if (source != null && !source.isBlank()) {
            where = where.and(INGESTION_RUN.SOURCE.eq(source));
        }
        if (status != null && !status.isBlank()) {
            where = where.and(INGESTION_RUN.STATUS.eq(status));
        }
        if (after != null) {
            where = where.and(DSL.row(INGESTION_RUN.STARTED_AT, INGESTION_RUN.ID)
                    .lt(DSL.row(after.at().atOffset(ZoneOffset.UTC), after.id())));
        }
        List<RunSummary> rows = dsl.selectFrom(INGESTION_RUN)
                .where(where)
                .orderBy(INGESTION_RUN.STARTED_AT.desc(), INGESTION_RUN.ID.desc())
                .limit(size + 1)
                .fetch(this::summary);
        if (rows.size() <= size) {
            return Page.of(rows);
        }
        RunSummary last = rows.get(size - 1);
        return new Page<>(rows.subList(0, size), new Cursor(last.startedAt(), last.id()).encode());
    }

    /** @throws ApiException 404 {@code RUN_NOT_FOUND} */
    @Transactional(readOnly = true)
    public RunDetail get(UUID id) {
        IngestionRunRecord row = dsl.selectFrom(INGESTION_RUN)
                .where(INGESTION_RUN.ID.eq(id))
                .fetchOptional()
                .orElseThrow(() -> ApiException.notFound("RUN_NOT_FOUND", "No ingestion run " + id));
        return new RunDetail(summary(row), stats(row), read(row.getIssues().data(), ISSUES));
    }

    /**
     * "Run now": queues a run of {@code source} for a scheduler process.
     *
     * @throws ApiException 400 {@code SOURCE_NOT_ENABLED} for an unknown source or one this deployment
     *     doesn't run; 409 {@code INGESTION_ALREADY_RUNNING} while the source has a queued or running run
     */
    public UUID queue(String source, UUID requestedBy) {
        if (!enabled(source, properties)) {
            throw ApiException.badRequest(
                    "SOURCE_NOT_ENABLED", "The gig source " + source + " is not enabled in this deployment");
        }
        return runs.enqueue(source, "CONSOLE", requestedBy, Instant.now(clock));
    }

    private Map<String, Object> stats(IngestionRunRecord row) {
        return read(row.getStats().data(), STATS);
    }

    private <T> T read(String data, TypeReference<T> type) {
        try {
            return json.readValue(data, type);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private RunSummary summary(IngestionRunRecord row) {
        Map<String, Object> stats = stats(row);
        Map<String, Integer> issueCounts = new LinkedHashMap<>();
        if (stats.get("issueCounts") instanceof Map<?, ?> counts) {
            counts.forEach(
                    (kind, n) -> issueCounts.put(String.valueOf(kind), n instanceof Number v ? v.intValue() : 0));
        }
        return new RunSummary(
                row.getId(),
                row.getSource(),
                row.getTrigger(),
                row.getStatus(),
                instant(row.getQueuedAt()),
                row.getStartedAt().toInstant(),
                instant(row.getFinishedAt()),
                row.getRequestedBy(),
                count(stats, "fetched"),
                count(stats, "created"),
                count(stats, "updated"),
                count(stats, "unchanged"),
                count(stats, "merged"),
                count(stats, "skipped"),
                count(stats, "errors"),
                count(stats, "apiCalls"),
                issueCounts,
                row.getError());
    }

    private static int count(Map<String, Object> stats, String key) {
        return stats.get(key) instanceof Number n ? n.intValue() : 0;
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime at) {
        return at == null ? null : at.toInstant();
    }
}
