package app.soundcheck.media.internal.worker;

import static app.soundcheck.shared.jooq.media.Tables.MEDIA_ASSET;
import static app.soundcheck.shared.jooq.media.Tables.TRANSCODE_JOB;

import app.soundcheck.media.MediaFailed;
import app.soundcheck.media.MediaReady;
import app.soundcheck.shared.Ids;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The worker's database steps, each one short transaction: claim, finalize, reject, fail,
 * release. A separate bean from the worker so that {@code @Transactional} applies (a call from inside
 * the worker's own class would bypass the proxy).
 */
@Component
@Transactional
public class TranscodeJobs {

    /** A successfully claimed asset: what the worker needs to process it. */
    public record Claim(
            UUID jobId, UUID mediaId, UUID ownerId, String originalKey, int attempt, String clientMetaJson) {}

    /** The stage timings of one attempt, in milliseconds (null = stage not reached). */
    public record Timings(
            @Nullable Integer downloadMs,
            @Nullable Integer probeMs,
            @Nullable Integer transcodeMs,
            @Nullable Integer uploadMs,
            int totalMs) {}

    /** Everything written when an asset becomes READY. */
    public record Outputs(
            String outputPrefix,
            String renditionsJson,
            String probeJson,
            String posterKey,
            String thumbKey,
            String blurhash,
            int durationMs,
            int width,
            int height) {}

    private final DSLContext dsl;
    private final ApplicationEventPublisher events;

    public TranscodeJobs(DSLContext dsl, ApplicationEventPublisher events) {
        this.dsl = dsl;
        this.events = events;
    }

    /**
     * The idempotent claim: one conditional UPDATE moves the asset to PROCESSING only if it is UPLOADED,
     * or PROCESSING under a lease that has expired (its worker died). Two workers holding the same
     * message cannot both get a row back. Returns null when nothing was claimed.
     */
    public @Nullable Claim claim(
            UUID mediaId, String workerId, Instant now, Duration lease, @Nullable Integer queueWaitMs) {
        var claimed = dsl.update(MEDIA_ASSET)
                .set(MEDIA_ASSET.STATUS, "PROCESSING")
                .set(MEDIA_ASSET.ATTEMPTS, MEDIA_ASSET.ATTEMPTS.plus(1))
                .set(MEDIA_ASSET.CLAIMED_BY, workerId)
                .set(MEDIA_ASSET.CLAIMED_AT, at(now))
                .set(MEDIA_ASSET.UPDATED_AT, at(now))
                .where(MEDIA_ASSET.ID.eq(mediaId))
                .and(MEDIA_ASSET
                        .STATUS
                        .eq("UPLOADED")
                        .or(MEDIA_ASSET.STATUS.eq("PROCESSING").and(MEDIA_ASSET.CLAIMED_AT.lt(at(now.minus(lease))))))
                .returning(
                        MEDIA_ASSET.ATTEMPTS, MEDIA_ASSET.OWNER_ID, MEDIA_ASSET.ORIGINAL_KEY, MEDIA_ASSET.CLIENT_META)
                .fetchOne();
        if (claimed == null) {
            return null;
        }
        // An attempt whose worker died is still RUNNING in the job table: close it.
        dsl.update(TRANSCODE_JOB)
                .set(TRANSCODE_JOB.STATUS, "FAILED")
                .set(TRANSCODE_JOB.ERROR, "lease expired: the worker stopped responding")
                .set(TRANSCODE_JOB.FINISHED_AT, at(now))
                .where(TRANSCODE_JOB.MEDIA_ID.eq(mediaId))
                .and(TRANSCODE_JOB.STATUS.eq("RUNNING"))
                .execute();
        UUID jobId = Ids.newId();
        int attempt = claimed.get(MEDIA_ASSET.ATTEMPTS);
        dsl.insertInto(TRANSCODE_JOB)
                .set(TRANSCODE_JOB.ID, jobId)
                .set(TRANSCODE_JOB.MEDIA_ID, mediaId)
                .set(TRANSCODE_JOB.ATTEMPT, attempt)
                .set(TRANSCODE_JOB.WORKER_ID, workerId)
                .set(TRANSCODE_JOB.STATUS, "RUNNING")
                .set(TRANSCODE_JOB.QUEUE_WAIT_MS, queueWaitMs)
                .set(TRANSCODE_JOB.STARTED_AT, at(now))
                .execute();
        return new Claim(
                jobId,
                mediaId,
                claimed.get(MEDIA_ASSET.OWNER_ID),
                claimed.get(MEDIA_ASSET.ORIGINAL_KEY),
                attempt,
                claimed.get(MEDIA_ASSET.CLIENT_META).data());
    }

    /**
     * Heartbeat: pushes the lease forward while the job is still running, so a long transcode is not
     * taken over. False when this worker no longer holds the attempt.
     */
    public boolean renewLease(Claim claim, String workerId, Instant now) {
        return dsl.update(MEDIA_ASSET)
                        .set(MEDIA_ASSET.CLAIMED_AT, at(now))
                        .where(held(claim, workerId))
                        .execute()
                == 1;
    }

    /** The asset's status, or null if there is no such asset (used when a claim returned nothing). */
    @Transactional(readOnly = true)
    public @Nullable String status(UUID mediaId) {
        return dsl.select(MEDIA_ASSET.STATUS)
                .from(MEDIA_ASSET)
                .where(MEDIA_ASSET.ID.eq(mediaId))
                .fetchOne(MEDIA_ASSET.STATUS);
    }

    /**
     * READY, job SUCCEEDED and {@link MediaReady} in one transaction. Conditional on this worker still
     * holding this attempt: false means the lease was lost (another worker took over) and nothing was written.
     */
    public boolean finalizeReady(Claim claim, String workerId, Outputs out, Timings timings, Instant now) {
        int updated = dsl.update(MEDIA_ASSET)
                .set(MEDIA_ASSET.STATUS, "READY")
                .set(MEDIA_ASSET.OUTPUT_PREFIX, out.outputPrefix())
                .set(MEDIA_ASSET.RENDITIONS, JSONB.valueOf(out.renditionsJson()))
                .set(MEDIA_ASSET.PROBE, JSONB.valueOf(out.probeJson()))
                .set(MEDIA_ASSET.POSTER_KEY, out.posterKey())
                .set(MEDIA_ASSET.THUMB_KEY, out.thumbKey())
                .set(MEDIA_ASSET.BLURHASH, out.blurhash())
                .set(MEDIA_ASSET.DURATION_MS, out.durationMs())
                .set(MEDIA_ASSET.WIDTH, out.width())
                .set(MEDIA_ASSET.HEIGHT, out.height())
                .set(MEDIA_ASSET.READY_AT, at(now))
                .set(MEDIA_ASSET.UPDATED_AT, at(now))
                .setNull(MEDIA_ASSET.CLAIMED_BY)
                .setNull(MEDIA_ASSET.CLAIMED_AT)
                .setNull(MEDIA_ASSET.FAILURE_REASON)
                .where(held(claim, workerId))
                .execute();
        if (updated == 0) {
            // Whoever took the asset over has already closed this attempt's job row.
            return false;
        }
        closeJob(claim.jobId(), "SUCCEEDED", null, timings, now);
        events.publishEvent(new MediaReady(claim.mediaId(), claim.ownerId(), now));
        return true;
    }

    /** What {@link #fail} did. */
    public enum Failure {
        /** Back to UPLOADED: it will be tried again. */
        RETRY,
        /** Out of attempts: FAILED, {@link MediaFailed} published. */
        TERMINAL,
        /** This worker no longer holds the attempt (another one took over, or it already finished): nothing written. */
        NOT_OWNER
    }

    /**
     * The file can never be transcoded: REJECTED, no retry, {@link MediaFailed}. Like every step after
     * the claim, it only applies while this worker still holds this attempt.
     *
     * @return false when it does not (nothing was written)
     */
    public boolean reject(Claim claim, String workerId, String reason, Timings timings, Instant now) {
        int updated = dsl.update(MEDIA_ASSET)
                .set(MEDIA_ASSET.STATUS, "REJECTED")
                .set(MEDIA_ASSET.FAILURE_REASON, reason)
                .set(MEDIA_ASSET.UPDATED_AT, at(now))
                .setNull(MEDIA_ASSET.CLAIMED_BY)
                .setNull(MEDIA_ASSET.CLAIMED_AT)
                .where(held(claim, workerId))
                .execute();
        if (updated == 0) {
            return false;
        }
        closeJob(claim.jobId(), "REJECTED", reason, timings, now);
        events.publishEvent(new MediaFailed(claim.mediaId(), claim.ownerId(), reason, true, now));
        return true;
    }

    /**
     * An attempt failed. Below the attempt limit the asset goes back to UPLOADED (lease cleared) to be
     * tried again; at the limit it is FAILED and {@link MediaFailed} is published.
     */
    public Failure fail(Claim claim, String workerId, String error, int maxAttempts, Timings timings, Instant now) {
        boolean terminal = claim.attempt() >= maxAttempts;
        int updated = dsl.update(MEDIA_ASSET)
                .set(MEDIA_ASSET.STATUS, terminal ? "FAILED" : "UPLOADED")
                .set(MEDIA_ASSET.FAILURE_REASON, error)
                .set(MEDIA_ASSET.UPDATED_AT, at(now))
                .setNull(MEDIA_ASSET.CLAIMED_BY)
                .setNull(MEDIA_ASSET.CLAIMED_AT)
                .where(held(claim, workerId))
                .execute();
        if (updated == 0) {
            return Failure.NOT_OWNER;
        }
        closeJob(claim.jobId(), "FAILED", error, timings, now);
        if (terminal) {
            events.publishEvent(new MediaFailed(claim.mediaId(), claim.ownerId(), error, false, now));
        }
        return terminal ? Failure.TERMINAL : Failure.RETRY;
    }

    /**
     * Shutdown mid-job: the attempt is handed back (it does not count) and the job row says RELEASED.
     *
     * @return false when this worker no longer held the attempt
     */
    public boolean release(Claim claim, String workerId, Timings timings, Instant now) {
        int updated = dsl.update(MEDIA_ASSET)
                .set(MEDIA_ASSET.STATUS, "UPLOADED")
                .set(MEDIA_ASSET.ATTEMPTS, MEDIA_ASSET.ATTEMPTS.minus(1))
                .set(MEDIA_ASSET.UPDATED_AT, at(now))
                .setNull(MEDIA_ASSET.CLAIMED_BY)
                .setNull(MEDIA_ASSET.CLAIMED_AT)
                .where(held(claim, workerId))
                .execute();
        if (updated == 0) {
            return false;
        }
        closeJob(claim.jobId(), "RELEASED", "worker shutting down", timings, now);
        return true;
    }

    /** "This worker still holds this attempt": the guard of every write after the claim. */
    private static Condition held(Claim claim, String workerId) {
        return MEDIA_ASSET
                .ID
                .eq(claim.mediaId())
                .and(MEDIA_ASSET.STATUS.eq("PROCESSING"))
                .and(MEDIA_ASSET.CLAIMED_BY.eq(workerId))
                .and(MEDIA_ASSET.ATTEMPTS.eq(claim.attempt()));
    }

    private void closeJob(UUID jobId, String status, @Nullable String error, Timings timings, Instant now) {
        dsl.update(TRANSCODE_JOB)
                .set(TRANSCODE_JOB.STATUS, status)
                .set(TRANSCODE_JOB.ERROR, error)
                .set(TRANSCODE_JOB.DOWNLOAD_MS, timings.downloadMs())
                .set(TRANSCODE_JOB.PROBE_MS, timings.probeMs())
                .set(TRANSCODE_JOB.TRANSCODE_MS, timings.transcodeMs())
                .set(TRANSCODE_JOB.UPLOAD_MS, timings.uploadMs())
                .set(TRANSCODE_JOB.TOTAL_MS, timings.totalMs())
                .set(TRANSCODE_JOB.FINISHED_AT, at(now))
                .where(TRANSCODE_JOB.ID.eq(jobId))
                .execute();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
