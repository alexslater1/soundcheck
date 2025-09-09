package app.soundcheck.media.internal;

import static app.soundcheck.shared.jooq.media.Tables.MEDIA_ASSET;
import static app.soundcheck.shared.jooq.media.Tables.TRANSCODE_JOB;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.percentileCont;
import static org.jooq.impl.DSL.selectOne;

import app.soundcheck.media.internal.worker.WorkerProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pipeline console's reads, and the one definition of a <b>stranded</b> clip, which the
 * console lists and {@link MediaAdmin} checks before Retry or Fail. A clip is stranded when
 * nothing will move it on by itself:
 *
 * <ul>
 *   <li>UPLOADED for more than {@link #UPLOADED_WAIT} with no RUNNING attempt: its queue message was lost
 *       or dead-lettered (the outbox re-sends a message that failed to send within a minute or two, so
 *       ten minutes is well past it);
 *   <li>or PROCESSING with a claim older than the worker's lease ({@code soundcheck.worker.lease}, 15 min):
 *       its worker died and no other worker has taken it over. The heartbeat renews {@code claimed_at}
 *       every 2 minutes, so a live job is never stranded however long it runs.
 * </ul>
 */
@Component
@Transactional(readOnly = true)
public class PipelineQueries {

    /** How long an UPLOADED clip may wait before it counts as stranded. */
    public static final Duration UPLOADED_WAIT = Duration.ofMinutes(10);

    /** Rows in the attention list and the recent-jobs table. */
    static final int LIST_LIMIT = 50;

    /** One asset the operator may need to act on. {@code since} is when it entered its status. */
    public record PipelineAsset(
            UUID id,
            UUID ownerId,
            String kind,
            String status,
            int attempts,
            @Schema(nullable = true) @Nullable String reason,
            Instant since,
            boolean stranded) {}

    /** One transcode attempt with its stage timings in milliseconds (null = stage not reached). */
    public record PipelineJob(
            UUID id,
            UUID mediaId,
            int attempt,
            String workerId,
            String status,
            @Schema(nullable = true) @Nullable Integer queueWaitMs,
            @Schema(nullable = true) @Nullable Integer downloadMs,
            @Schema(nullable = true) @Nullable Integer probeMs,
            @Schema(nullable = true) @Nullable Integer transcodeMs,
            @Schema(nullable = true) @Nullable Integer uploadMs,
            @Schema(nullable = true) @Nullable Integer totalMs,
            @Schema(nullable = true) @Nullable String error,
            Instant startedAt,
            @Schema(nullable = true) @Nullable Instant finishedAt) {}

    public record PipelineStatusCount(String status, int count) {}

    /** p50/p95 of one stage over the range; null when no attempt reached it. */
    public record PipelineStage(
            String stage,
            int count,
            @Schema(nullable = true) @Nullable Double p50Ms,
            @Schema(nullable = true) @Nullable Double p95Ms) {}

    public record PipelineFailureReason(String reason, int count) {}

    /** The transcode stage's p50/p95 on one UTC day (successful attempts only). */
    public record PipelineDay(LocalDate day, int count, double p50Ms, double p95Ms) {}

    /** The stages in pipeline order, with the column that times each. */
    private static final List<Stage> STAGES = List.of(
            new Stage("queueWait", TRANSCODE_JOB.QUEUE_WAIT_MS),
            new Stage("download", TRANSCODE_JOB.DOWNLOAD_MS),
            new Stage("probe", TRANSCODE_JOB.PROBE_MS),
            new Stage("transcode", TRANSCODE_JOB.TRANSCODE_MS),
            new Stage("upload", TRANSCODE_JOB.UPLOAD_MS),
            new Stage("total", TRANSCODE_JOB.TOTAL_MS));

    private record Stage(String name, Field<Integer> column) {}

    private final DSLContext dsl;
    private final Duration lease;

    PipelineQueries(DSLContext dsl, WorkerProperties worker) {
        // WorkerProperties is bound in every role (MediaConfig), so the API reads the lease without
        // depending on any worker bean.
        this.dsl = dsl;
        this.lease = worker.lease();
    }

    /** The worker's lease: a PROCESSING claim older than this is abandoned. */
    public Duration lease() {
        return lease;
    }

    /**
     * The stranded rule as a condition on {@code media.media_asset}. Expected index: media_asset_status
     * (status, updated_at) for both branches, then transcode_job_media (media_id, attempt) for the
     * "no RUNNING attempt" check.
     */
    Condition stranded(Instant now) {
        Condition noRunningAttempt = notExists(selectOne()
                .from(TRANSCODE_JOB)
                .where(TRANSCODE_JOB.MEDIA_ID.eq(MEDIA_ASSET.ID))
                .and(TRANSCODE_JOB.STATUS.eq("RUNNING")));
        Condition lostMessage = MEDIA_ASSET
                .STATUS
                .eq("UPLOADED")
                .and(MEDIA_ASSET.UPDATED_AT.lt(at(now.minus(UPLOADED_WAIT))))
                .and(noRunningAttempt);
        Condition deadWorker = MEDIA_ASSET.STATUS.eq("PROCESSING").and(MEDIA_ASSET.CLAIMED_AT.lt(at(now.minus(lease))));
        return lostMessage.or(deadWorker);
    }

    /** Whether this asset is stranded now. Expected index: the primary key. */
    public boolean isStranded(UUID mediaId, Instant now) {
        return dsl.fetchExists(MEDIA_ASSET, MEDIA_ASSET.ID.eq(mediaId).and(stranded(now)));
    }

    /**
     * Assets in each status. Expected plan: a sequential scan of media_asset (one row per upload; tens of
     * thousands at most here), cheap enough for one operator's 5-second refresh.
     */
    public List<PipelineStatusCount> statusCounts() {
        return dsl.select(MEDIA_ASSET.STATUS, count())
                .from(MEDIA_ASSET)
                .groupBy(MEDIA_ASSET.STATUS)
                .orderBy(MEDIA_ASSET.STATUS)
                .fetch(r -> new PipelineStatusCount(r.value1(), r.value2()));
    }

    /**
     * Stranded and FAILED assets, most recent first. Expected index: media_asset_status (its partial
     * predicate covers UPLOADED, PROCESSING and FAILED).
     */
    public List<PipelineAsset> attention(Instant now) {
        Field<Boolean> stranded = DSL.field(stranded(now));
        Field<OffsetDateTime> since = DSL.coalesce(
                DSL.when(MEDIA_ASSET.STATUS.eq("PROCESSING"), MEDIA_ASSET.CLAIMED_AT), MEDIA_ASSET.UPDATED_AT);
        return dsl.select(
                        MEDIA_ASSET.ID,
                        MEDIA_ASSET.OWNER_ID,
                        MEDIA_ASSET.KIND,
                        MEDIA_ASSET.STATUS,
                        MEDIA_ASSET.ATTEMPTS,
                        MEDIA_ASSET.FAILURE_REASON,
                        since,
                        stranded)
                .from(MEDIA_ASSET)
                .where(MEDIA_ASSET.STATUS.in("UPLOADED", "PROCESSING", "FAILED"))
                .and(MEDIA_ASSET.STATUS.eq("FAILED").or(stranded(now)))
                .orderBy(since.desc(), MEDIA_ASSET.ID.desc())
                .limit(LIST_LIMIT)
                .fetch(r -> new PipelineAsset(
                        r.value1(),
                        r.value2(),
                        r.value3(),
                        r.value4(),
                        r.value5(),
                        r.value6(),
                        r.value7().toInstant(),
                        Boolean.TRUE.equals(r.value8())));
    }

    /** The latest attempts, newest first. Expected index: transcode_job_started (started_at desc), V140. */
    public List<PipelineJob> recentJobs() {
        return dsl.selectFrom(TRANSCODE_JOB)
                .orderBy(TRANSCODE_JOB.STARTED_AT.desc(), TRANSCODE_JOB.ID.desc())
                .limit(LIST_LIMIT)
                .fetch(r -> new PipelineJob(
                        r.getId(),
                        r.getMediaId(),
                        r.getAttempt(),
                        r.getWorkerId(),
                        r.getStatus(),
                        r.getQueueWaitMs(),
                        r.getDownloadMs(),
                        r.getProbeMs(),
                        r.getTranscodeMs(),
                        r.getUploadMs(),
                        r.getTotalMs(),
                        r.getError(),
                        r.getStartedAt().toInstant(),
                        r.getFinishedAt() == null ? null : r.getFinishedAt().toInstant()));
    }

    /**
     * p50/p95 of each stage over the attempts started in [from, to), every attempt that reached the stage
     * (a failed attempt still timed the stages before its failure). Expected index: transcode_job_started.
     */
    public List<PipelineStage> stagePercentiles(Instant from, Instant to) {
        List<Field<?>> fields = STAGES.stream()
                .<Field<?>>mapMulti((stage, out) -> {
                    out.accept(count(stage.column()));
                    out.accept(percentileCont(0.5).withinGroupOrderBy(stage.column()));
                    out.accept(percentileCont(0.95).withinGroupOrderBy(stage.column()));
                })
                .toList();
        var row =
                dsl.select(fields).from(TRANSCODE_JOB).where(inRange(from, to)).fetchSingle();
        return STAGES.stream()
                .map(stage -> {
                    int i = STAGES.indexOf(stage) * 3;
                    int n = row.get(i, Integer.class);
                    return new PipelineStage(
                            stage.name(),
                            n,
                            n == 0 ? null : row.get(i + 1, Double.class),
                            n == 0 ? null : row.get(i + 2, Double.class));
                })
                .toList();
    }

    /**
     * Failed and rejected attempts in [from, to), grouped by the error's first clause ("ffmpeg exited
     * with 1: …" → "ffmpeg exited with 1"), most frequent first. Expected index: transcode_job_started.
     */
    public List<PipelineFailureReason> failuresByReason(Instant from, Instant to) {
        Field<String> reason = DSL.left(
                        DSL.coalesce(
                                DSL.nullif(DSL.trim(DSL.splitPart(TRANSCODE_JOB.ERROR, ":", 1)), ""),
                                DSL.val("unknown")),
                        80)
                .as("reason");
        return dsl.select(reason, count())
                .from(TRANSCODE_JOB)
                .where(inRange(from, to))
                .and(TRANSCODE_JOB.STATUS.in("FAILED", "REJECTED"))
                .groupBy(reason)
                .orderBy(count().desc(), reason)
                .limit(20)
                .fetch(r -> new PipelineFailureReason(r.value1(), r.value2()));
    }

    /**
     * The transcode stage's p50/p95 per UTC day, successful attempts started in [from, to). Expected
     * index: transcode_job_started.
     */
    public List<PipelineDay> dailyTranscode(Instant from, Instant to) {
        Field<LocalDate> day = DSL.field(
                        "({0} at time zone 'UTC')::date", SQLDataType.LOCALDATE, TRANSCODE_JOB.STARTED_AT)
                .as("day");
        return dsl.select(
                        day,
                        count(),
                        percentileCont(0.5).withinGroupOrderBy(TRANSCODE_JOB.TRANSCODE_MS),
                        percentileCont(0.95).withinGroupOrderBy(TRANSCODE_JOB.TRANSCODE_MS))
                .from(TRANSCODE_JOB)
                .where(inRange(from, to))
                .and(TRANSCODE_JOB.STATUS.eq("SUCCEEDED"))
                .and(TRANSCODE_JOB.TRANSCODE_MS.isNotNull())
                .groupBy(day)
                .orderBy(day)
                .fetch(r -> new PipelineDay(r.value1(), r.value2(), r.get(2, Double.class), r.get(3, Double.class)));
    }

    private static Condition inRange(Instant from, Instant to) {
        return TRANSCODE_JOB.STARTED_AT.ge(at(from)).and(TRANSCODE_JOB.STARTED_AT.lt(at(to)));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
