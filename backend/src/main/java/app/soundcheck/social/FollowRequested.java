package app.soundcheck.social;

import java.util.UUID;

/** Domain event: someone asked to follow a private profile; a repeated ask publishes nothing. */
public record FollowRequested(UUID requesterId, UUID ownerId) {}
