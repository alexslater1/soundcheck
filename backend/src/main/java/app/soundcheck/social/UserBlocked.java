package app.soundcheck.social;

import java.util.UUID;

/** Domain event: a block was actually added (a repeated block publishes nothing). */
public record UserBlocked(UUID blockerId, UUID blockedId) {}
