package app.soundcheck.media;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event: an operator sent a failed or stranded clip back to the
 * transcode queue. Content moves a post that failed only because of this clip back to PENDING_MEDIA, so
 * the next {@link MediaReady} publishes it as usual. Published together with {@link MediaUploaded} (which
 * the relay turns into a queue message after the commit).
 */
public record MediaRequeued(UUID mediaId, UUID ownerId, Instant requeuedAt) {}
