package app.soundcheck.media;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event: a video will not become playable. {@code rejected} is true when the file
 * itself is unusable (not a video, too long…), false when processing failed three times.
 */
public record MediaFailed(UUID mediaId, UUID ownerId, String reason, boolean rejected, Instant failedAt) {}
