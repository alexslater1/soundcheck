package app.soundcheck.social;

import java.util.UUID;

/**
 * Domain event: a follow request ended without a follow: the owner declined it or the
 * requester cancelled it. The owner's Activity entry for it goes. (A block removes requests silently: the
 * block already clears the inbox between the two.)
 */
public record FollowRequestWithdrawn(UUID requesterId, UUID ownerId) {}
