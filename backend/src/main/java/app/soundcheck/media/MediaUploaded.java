package app.soundcheck.media;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Domain event: a video's bytes are in object storage and it is waiting to be transcoded.
 * Published in the same transaction that sets the asset to UPLOADED, so it is recorded in the outbox
 * and relayed to the transcode queue after the commit.
 *
 * @param traceparent the W3C trace context of the request that completed the upload, carried inside the
 *     event so the trace continues through the queue to the worker even if delivery happens later, on
 *     another thread or after a restart; null when tracing is off
 */
public record MediaUploaded(UUID mediaId, UUID ownerId, Instant uploadedAt, @Nullable String traceparent) {}
