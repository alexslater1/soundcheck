package app.soundcheck.media.internal;

import app.soundcheck.media.JobQueue;
import app.soundcheck.media.JobQueue.QueueAttributes;
import app.soundcheck.media.MediaFailed;
import app.soundcheck.media.MediaRequeued;
import app.soundcheck.media.MediaUploaded;
import app.soundcheck.media.internal.PipelineQueries.PipelineAsset;
import app.soundcheck.media.internal.PipelineQueries.PipelineDay;
import app.soundcheck.media.internal.PipelineQueries.PipelineFailureReason;
import app.soundcheck.media.internal.PipelineQueries.PipelineJob;
import app.soundcheck.media.internal.PipelineQueries.PipelineStage;
import app.soundcheck.media.internal.PipelineQueries.PipelineStatusCount;
import app.soundcheck.shared.observability.Traceparent;
import app.soundcheck.shared.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pipeline console's service: what the operator sees, and the two things they
 * can do to a clip the pipeline gave up on or lost.
 *
 * <ul>
 *   <li><b>Retry</b> a FAILED or stranded clip: it goes back in line with a fresh set of attempts, and a
 *       post that failed only because of it comes back to PENDING_MEDIA.
 *   <li><b>Fail</b> a stranded clip: FAILED with reason {@value #OPERATOR}, and its post fails through the
 *       normal {@link MediaFailed} path.
 * </ul>
 *
 * Neither calls SQS: the queue message is sent by the outbox relay after the commit, as for an upload.
 */
@Service
public class MediaAdmin {

    private static final Logger log = LoggerFactory.getLogger(MediaAdmin.class);

    /** The failure reason an operator's Fail records. */
    public static final String OPERATOR = "OPERATOR";

    /** The widest range the read endpoint accepts (as the analytics endpoints). */
    static final Duration MAX_RANGE = Duration.ofDays(90);

    static final Duration DEFAULT_RANGE = Duration.ofDays(7);

    /** Queue depth; null fields in {@link PipelineOverview} when the queue could not be read. */
    public record PipelineQueueDepth(int visible, int inFlight, int delayed) {}

    /** The dead-letter queue's depth: messages the worker gave up on (watched, never redriven). */
    public record PipelineDlqDepth(int visible) {}

    /** The thresholds of the stranded rule, so the page can explain it. */
    public record PipelineStrandedRule(int uploadedMinutes, int leaseMinutes) {}

    /**
     * Everything the pipeline page shows. {@code queue} and {@code dlq} are null when SQS could not be
     * reached ({@code queueError = "unreachable"}); {@code dlq} alone is null when no DLQ is configured.
     */
    public record PipelineOverview(
            Instant from,
            Instant to,
            Instant generatedAt,
            @Schema(nullable = true) @Nullable PipelineQueueDepth queue,
            @Schema(nullable = true) @Nullable PipelineDlqDepth dlq,
            @Schema(nullable = true) @Nullable String queueError,
            PipelineStrandedRule strandedRule,
            List<PipelineStatusCount> statuses,
            List<PipelineAsset> attention,
            List<PipelineJob> recentJobs,
            List<PipelineStage> stages,
            List<PipelineFailureReason> failuresByReason,
            List<PipelineDay> dailyTranscode) {}

    /** What an action did: the asset's status before and after. */
    public record PipelineAction(UUID mediaId, String previousStatus, String status) {}

    private final MediaAssetRepository assets;
    private final PipelineQueries pipeline;
    private final JobQueue queue;
    private final ApplicationEventPublisher events;
    private final Traceparent traceparent;
    private final Clock clock;

    MediaAdmin(
            MediaAssetRepository assets,
            PipelineQueries pipeline,
            JobQueue queue,
            ApplicationEventPublisher events,
            Traceparent traceparent,
            Clock clock) {
        this.assets = assets;
        this.pipeline = pipeline;
        this.queue = queue;
        this.events = events;
        this.traceparent = traceparent;
        this.clock = clock;
    }

    /**
     * The page's data for [from, to) (default: the last 7 days). The queue is read outside any
     * transaction, with a short timeout: an unreachable SQS gives null counts, never a 500.
     */
    public PipelineOverview overview(@Nullable Instant from, @Nullable Instant to) {
        Instant now = clock.instant();
        Instant end = to == null ? now : to;
        Instant start = from == null ? end.minus(DEFAULT_RANGE) : from;
        if (!start.isBefore(end)) {
            throw ApiException.badRequest("RANGE_INVALID", "from must be before to");
        }
        if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw ApiException.badRequest("RANGE_TOO_LONG", "The range can be at most 90 days");
        }
        QueueSnapshot snapshot = queueSnapshot(queue);
        return new PipelineOverview(
                start,
                end,
                now,
                snapshot.queue(),
                snapshot.dlq(),
                snapshot.error(),
                new PipelineStrandedRule((int) PipelineQueries.UPLOADED_WAIT.toMinutes(), (int)
                        pipeline.lease().toMinutes()),
                pipeline.statusCounts(),
                pipeline.attention(now),
                pipeline.recentJobs(),
                pipeline.stagePercentiles(start, end),
                pipeline.failuresByReason(start, end),
                pipeline.dailyTranscode(start, end));
    }

    /** The queue and DLQ counts, or nulls and an error when SQS can't be reached. */
    record QueueSnapshot(@Nullable PipelineQueueDepth queue, @Nullable PipelineDlqDepth dlq, @Nullable String error) {}

    static QueueSnapshot queueSnapshot(JobQueue queue) {
        try {
            QueueAttributes main = queue.attributes();
            PipelineDlqDepth dlq = queue.deadLetterAttributes()
                    .map(d -> new PipelineDlqDepth(d.visible()))
                    .orElse(null);
            return new QueueSnapshot(
                    new PipelineQueueDepth(main.visible(), main.inFlight(), main.delayed()), dlq, null);
        } catch (RuntimeException e) {
            // Not the URL: queue URLs are not secret, but the message may carry a request id; keep it short.
            log.warn(
                    "pipeline console could not read the queue: {}",
                    e.getClass().getSimpleName());
            return new QueueSnapshot(null, null, "unreachable");
        }
    }

    /**
     * Sends a FAILED or stranded clip back to the transcode queue, in one transaction with the row
     * locked. <b>Policy: a manual retry grants a fresh set of attempts</b> ({@code attempts = 0}), so the
     * job history shows a second "attempt 1" after it; the operator decided the earlier failures should
     * not count (a fixed bug, a worker that was out of disk). A REJECTED clip is refused: the file itself
     * is unusable and would only be rejected again.
     *
     * <p>Publishes {@link MediaUploaded} (the relay sends the queue message after the commit, as for an
     * upload) and {@link MediaRequeued} (content brings back a post that failed only because of this
     * clip). If a message for the clip is still in the queue or the DLQ, the worker's idempotent claim
     * makes the second delivery a no-op.
     */
    @Transactional
    public PipelineAction retry(UUID mediaId) {
        Instant now = clock.instant();
        MediaAsset asset = assets.findForUpdate(mediaId).orElseThrow(MediaAdmin::notFound);
        if (asset.status().equals("REJECTED")) {
            throw ApiException.conflict("MEDIA_REJECTED", "The file itself can't be transcoded: retrying won't help");
        }
        boolean retryable = asset.video() && (asset.status().equals("FAILED") || pipeline.isStranded(mediaId, now));
        if (!retryable) {
            throw ApiException.conflict(
                    "MEDIA_NOT_RETRYABLE",
                    "Only a failed or stranded video can be retried; this one is " + asset.status());
        }
        assets.closeRunningJobs(mediaId, "closed by an operator: retried", now);
        assets.requeue(mediaId, now);
        events.publishEvent(new MediaUploaded(
                mediaId, asset.ownerId(), now, traceparent.current().orElse(null)));
        events.publishEvent(new MediaRequeued(mediaId, asset.ownerId(), now));
        return new PipelineAction(mediaId, asset.status(), "UPLOADED");
    }

    /**
     * Gives up on a stranded clip: FAILED with reason {@value #OPERATOR}, and {@link MediaFailed} (not
     * rejected) so its post fails through the normal path. Only a stranded clip: a FAILED one already is,
     * and a live one is still being worked on.
     */
    @Transactional
    public PipelineAction fail(UUID mediaId) {
        Instant now = clock.instant();
        MediaAsset asset = assets.findForUpdate(mediaId).orElseThrow(MediaAdmin::notFound);
        if (!pipeline.isStranded(mediaId, now)) {
            throw ApiException.conflict(
                    "MEDIA_NOT_STRANDED", "Only a stranded clip can be failed; this one is " + asset.status());
        }
        assets.closeRunningJobs(mediaId, OPERATOR + ": failed by an operator", now);
        assets.failByOperator(mediaId, OPERATOR, now);
        events.publishEvent(new MediaFailed(mediaId, asset.ownerId(), OPERATOR, false, now));
        return new PipelineAction(mediaId, asset.status(), "FAILED");
    }

    private static ApiException notFound() {
        return ApiException.notFound("MEDIA_NOT_FOUND", "No such media");
    }
}
