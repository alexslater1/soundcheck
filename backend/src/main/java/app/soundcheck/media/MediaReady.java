package app.soundcheck.media;

import java.time.Instant;
import java.util.UUID;

/** Domain event: a video finished transcoding and can be played. Consumed by content (publish the post) and notifications. */
public record MediaReady(UUID mediaId, UUID ownerId, Instant readyAt) {}
