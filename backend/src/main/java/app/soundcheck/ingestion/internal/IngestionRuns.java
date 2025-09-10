package app.soundcheck.ingestion.internal;

import static app.soundcheck.shared.jooq.ingestion.Tables.INGESTION_RUN;
import static app.soundcheck.shared.jooq.ingestion.Tables.RAW_PAYLOAD;

import app.soundcheck.ingestion.GigsIngested;
import app.soundcheck.shared.Ids;
import app.soundcheck.shared.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bookkeeping for ingestion runs ({@code ingestion.ingestion_run}, {@code raw_payload}). Each
 * method is its own short transaction: a run lasts minutes and must not hold one transaction open, and
 * its row has to be visible (as RUNNING) to a second trigger while it works.
 */
@Service
@Transactional
public class IngestionRuns {

    /**
     * A RUNNING row whose heartbeat is older than this belongs to a process that died (Ctrl-C, OOM kill);
     * the next trigger closes it as FAILED. A live run beats at least every 30 seconds; the longest silence
     * it can have is one call's retries (a few minutes at most).
     */
    static final Duration ABANDONED_AFTER = Duration.ofMinutes(10);

    private final DSLContext dsl;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;

    public IngestionRuns(DSLContext dsl, JsonMapper json, ApplicationEventPublisher events) {
        this.dsl = dsl;
        this.json = json;
        this.events = events;
    }

    /**
     * A QUEUED row no scheduler process claimed within this long is closed as FAILED by the next trigger
     * of its source, so a stack without a scheduler can't block "Run now" for good. Two hours is
     * the poller's {@code lockAtMostFor}: a scheduler that is alive claims a queued run within seconds.
     */
    static final Duration UNCLAIMED_AFTER = Duration.ofHours(2);

    /**
     * Opens a run. The partial unique index {@code ingestion_run_one_active} (V144) allows one QUEUED or
     * RUNNING row per source, so a concurrent trigger fails here instead of ingesting the same pages twice.
     *
     * @throws ApiException 409 {@code INGESTION_ALREADY_RUNNING}
     */
    public UUID start(String source, String trigger, Instant now) {
        closeAbandoned(source, now);
        UUID id = Ids.newId();
        insertOrConflict(source, () -> dsl.insertInto(INGESTION_RUN)
                .set(INGESTION_RUN.ID, id)
                .set(INGESTION_RUN.SOURCE, source)
                .set(INGESTION_RUN.TRIGGER, trigger)
                .set(INGESTION_RUN.STATUS, "RUNNING")
                .set(INGESTION_RUN.STARTED_AT, at(now))
                .set(INGESTION_RUN.HEARTBEAT_AT, at(now))
                .execute());
        return id;
    }

    /**
     * "Run now": records a QUEUED run for a scheduler process to claim. The same index as
     * {@link #start} refuses it while the source has a queued or running run.
     *
     * @throws ApiException 409 {@code INGESTION_ALREADY_RUNNING}
     */
    public UUID enqueue(String source, String trigger, UUID requestedBy, Instant now) {
        closeAbandoned(source, now);
        UUID id = Ids.newId();
        insertOrConflict(source, () -> dsl.insertInto(INGESTION_RUN)
                .set(INGESTION_RUN.ID, id)
                .set(INGESTION_RUN.SOURCE, source)
                .set(INGESTION_RUN.TRIGGER, trigger)
                .set(INGESTION_RUN.STATUS, "QUEUED")
                .set(INGESTION_RUN.QUEUED_AT, at(now))
                .set(INGESTION_RUN.STARTED_AT, at(now)) // the real start is set by the claim
                .set(INGESTION_RUN.REQUESTED_BY, requestedBy)
                .execute());
        return id;
    }

    /**
     * Claims a QUEUED run: QUEUED → RUNNING, conditionally, so of two pollers that read the same queued
     * row only one gets it (the other updates nothing and returns empty).
     *
     * @return the run's source, or empty when the run is no longer QUEUED
     */
    public Optional<String> claim(UUID runId, Instant now) {
        return dsl.update(INGESTION_RUN)
                .set(INGESTION_RUN.STATUS, "RUNNING")
                .set(INGESTION_RUN.STARTED_AT, at(now))
                .set(INGESTION_RUN.HEARTBEAT_AT, at(now))
                .where(INGESTION_RUN.ID.eq(runId))
                .and(INGESTION_RUN.STATUS.eq("QUEUED"))
                .returning(INGESTION_RUN.SOURCE)
                .fetchOptional(INGESTION_RUN.SOURCE);
    }

    /** The run queued first, if any. A handful of rows; ingestion_run_one_active holds every QUEUED one. */
    @Transactional(readOnly = true)
    public Optional<UUID> oldestQueued() {
        return dsl.select(INGESTION_RUN.ID)
                .from(INGESTION_RUN)
                .where(INGESTION_RUN.STATUS.eq("QUEUED"))
                .orderBy(INGESTION_RUN.STARTED_AT, INGESTION_RUN.ID)
                .limit(1)
                .fetchOptional(INGESTION_RUN.ID);
    }

    /**
     * Before a new run of {@code source}: a RUNNING row whose heartbeat went silent belongs to a process
     * that died, and a QUEUED row nobody claimed for {@link #UNCLAIMED_AFTER} has no scheduler to run it.
     * Both are closed as FAILED, so neither blocks the source.
     */
    private void closeAbandoned(String source, Instant now) {
        dsl.update(INGESTION_RUN)
                .set(INGESTION_RUN.STATUS, "FAILED")
                .set(INGESTION_RUN.FINISHED_AT, at(now))
                .set(INGESTION_RUN.ERROR, "abandoned: still RUNNING after " + ABANDONED_AFTER)
                .where(INGESTION_RUN.SOURCE.eq(source))
                .and(INGESTION_RUN.STATUS.eq("RUNNING"))
                .and(DSL.coalesce(INGESTION_RUN.HEARTBEAT_AT, INGESTION_RUN.STARTED_AT)
                        .lt(at(now.minus(ABANDONED_AFTER))))
                .execute();
        dsl.update(INGESTION_RUN)
                .set(INGESTION_RUN.STATUS, "FAILED")
                .set(INGESTION_RUN.FINISHED_AT, at(now))
                .set(INGESTION_RUN.ERROR, "never claimed: still QUEUED after " + UNCLAIMED_AFTER)
                .where(INGESTION_RUN.SOURCE.eq(source))
                .and(INGESTION_RUN.STATUS.eq("QUEUED"))
                .and(INGESTION_RUN.STARTED_AT.lt(at(now.minus(UNCLAIMED_AFTER))))
                .execute();
    }

    private static void insertOrConflict(String source, Runnable insert) {
        try {
            insert.run();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict(
                    "INGESTION_ALREADY_RUNNING", "An ingestion run for " + source + " is queued or in progress");
        }
    }

    /** "Still alive": see {@link #ABANDONED_AFTER}. */
    public void heartbeat(UUID runId, Instant now) {
        dsl.update(INGESTION_RUN)
                .set(INGESTION_RUN.HEARTBEAT_AT, at(now))
                .where(INGESTION_RUN.ID.eq(runId))
                .and(INGESTION_RUN.STATUS.eq("RUNNING"))
                .execute();
    }

    /** Keeps a raw response replayable; {@code request} never contains secrets. */
    public void savePayload(UUID runId, String source, String request, JsonNode body, Instant now) {
        dsl.insertInto(RAW_PAYLOAD)
                .set(RAW_PAYLOAD.ID, Ids.newId())
                .set(RAW_PAYLOAD.RUN_ID, runId)
                .set(RAW_PAYLOAD.SOURCE, source)
                .set(RAW_PAYLOAD.REQUEST, request)
                .set(RAW_PAYLOAD.BODY, JSONB.valueOf(toJson(body)))
                .set(RAW_PAYLOAD.FETCHED_AT, at(now))
                .execute();
    }

    /** Closes the run and publishes {@link GigsIngested} in the same transaction (outbox). */
    public void finish(UUID runId, String source, String status, RunStats stats, @Nullable String error, Instant now) {
        dsl.update(INGESTION_RUN)
                .set(INGESTION_RUN.STATUS, status)
                .set(INGESTION_RUN.FINISHED_AT, at(now))
                .set(INGESTION_RUN.STATS, JSONB.valueOf(toJson(stats.toMap())))
                .set(INGESTION_RUN.ISSUES, JSONB.valueOf(toJson(stats.issueSample())))
                .set(INGESTION_RUN.ERROR, error)
                .where(INGESTION_RUN.ID.eq(runId))
                .and(INGESTION_RUN.STATUS.eq("RUNNING")) // a run already closed as abandoned stays closed
                .execute();
        events.publishEvent(new GigsIngested(runId, source, status, stats.created, stats.updated, stats.merged, now));
    }

    /** When the latest SUCCEEDED run of this source before {@code runId} started, if there was one. */
    @Transactional(readOnly = true)
    public Optional<Instant> previousSuccessStart(String source, UUID runId) {
        return dsl.select(INGESTION_RUN.STARTED_AT)
                .from(INGESTION_RUN)
                .where(INGESTION_RUN.SOURCE.eq(source))
                .and(INGESTION_RUN.STATUS.eq("SUCCEEDED"))
                .and(INGESTION_RUN.ID.ne(runId))
                .orderBy(INGESTION_RUN.STARTED_AT.desc())
                .limit(1)
                .fetchOptional(INGESTION_RUN.STARTED_AT)
                .map(OffsetDateTime::toInstant);
    }

    /**
     * When the latest earlier run of this source that read {@code group} in full started: its stats
     * list the group under {@link RunStats#GROUPS_READ_IN_FULL}. A few runs a day; no index needed.
     */
    @Transactional(readOnly = true)
    public Optional<Instant> previousReadInFull(String source, String group, UUID runId) {
        String containsGroup = toJson(Map.of(RunStats.GROUPS_READ_IN_FULL, List.of(group)));
        return dsl.select(INGESTION_RUN.STARTED_AT)
                .from(INGESTION_RUN)
                .where(INGESTION_RUN.SOURCE.eq(source))
                .and(INGESTION_RUN.STATUS.in("SUCCEEDED", "PARTIAL"))
                .and(INGESTION_RUN.ID.ne(runId))
                .and(DSL.condition("{0} @> {1}::jsonb", INGESTION_RUN.STATS, DSL.val(containsGroup)))
                .orderBy(INGESTION_RUN.STARTED_AT.desc())
                .limit(1)
                .fetchOptional(INGESTION_RUN.STARTED_AT)
                .map(OffsetDateTime::toInstant);
    }

    /** Deletes raw payloads fetched before {@code before}; expected index: raw_payload_fetched. */
    public int prunePayloads(Instant before) {
        return dsl.deleteFrom(RAW_PAYLOAD)
                .where(RAW_PAYLOAD.FETCHED_AT.lt(at(before)))
                .execute();
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
