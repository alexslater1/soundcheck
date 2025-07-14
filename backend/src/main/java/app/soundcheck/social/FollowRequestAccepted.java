package app.soundcheck.social;

import java.util.UUID;

/**
 * Domain event: a follow request was accepted and the requester now follows the owner
 *, by the owner or because the profile turned public. Published instead of {@link UserFollowed}:
 * the owner chose this follower, so it is not news to them.
 */
public record FollowRequestAccepted(UUID requesterId, UUID ownerId) {}
