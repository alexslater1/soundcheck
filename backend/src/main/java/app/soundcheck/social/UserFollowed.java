package app.soundcheck.social;

import java.util.UUID;

/** Domain event: a follow was actually added (a repeated follow publishes nothing). */
public record UserFollowed(UUID followerId, UUID followeeId) {}
