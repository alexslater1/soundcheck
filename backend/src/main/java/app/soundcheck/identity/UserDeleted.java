package app.soundcheck.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event: an account was soft-deleted and all its sessions revoked. Consumers
 * (content purge, signals anonymisation, notifications) react via {@code @ApplicationModuleListener};
 * the 7-day purge itself runs later, through {@link AccountPurgeStep}.
 */
public record UserDeleted(UUID userId, Instant deletedAt) {}
