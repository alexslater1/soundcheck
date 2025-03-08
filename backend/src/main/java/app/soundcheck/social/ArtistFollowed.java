package app.soundcheck.social;

import java.time.Instant;
import java.util.UUID;

/** Domain event: a user started following an artist. Consumed by notifications and signals later. */
public record ArtistFollowed(UUID userId, UUID artistId, Instant at) {}
