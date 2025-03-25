package app.soundcheck.media;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event: a video's bytes are in object storage and it is waiting to be transcoded.
 * Published in the same transaction that sets the asset to UPLOADED, so it is recorded in the outbox
 * and relayed to the transcode queue after the commit.
 */
public record MediaUploaded(UUID mediaId, UUID ownerId, Instant uploadedAt) {}
