package app.soundcheck.social;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Social's read API for other modules: who follows whom, and who has blocked whom. A block hides two
 * people from each other whichever of them made it, so the questions other modules ask are symmetric
 * ("is there a block between these two?").
 */
public interface SocialGraph {

    /** Which of the given users the viewer follows. */
    Set<UUID> followedAmong(UUID viewerId, Collection<UUID> userIds);

    /** True when either user has blocked the other. */
    boolean blockBetween(UUID a, UUID b);

    /** The users among {@code userIds} with a block between them and the viewer (either direction). */
    Set<UUID> blockedAmong(UUID viewerId, Collection<UUID> userIds);

    /** True when {@code blockerId} has blocked {@code blockedId} (this direction only). */
    boolean hasBlocked(UUID blockerId, UUID blockedId);

    /** Everyone the viewer follows (ids), for the Following feed. */
    Set<UUID> followeeIds(UUID viewerId);

    long followerCount(UUID userId);

    long followingCount(UUID userId);
}
