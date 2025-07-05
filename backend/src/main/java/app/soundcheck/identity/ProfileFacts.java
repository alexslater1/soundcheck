package app.soundcheck.identity;

import java.util.UUID;

/**
 * What a profile shows that identity does not own: follower and post counts, and how the viewer relates
 * to the profile's owner. Identity declares the question; the content module (which may depend on
 * identity and social) answers it, so identity keeps depending on nothing but shared.
 */
public interface ProfileFacts {

    Counts counts(UUID userId);

    Relation relation(UUID viewerId, UUID profileUserId);

    record Counts(long followers, long following, long posts) {}

    /**
     * @param following the viewer follows this user
     * @param blocked the viewer has blocked this user
     * @param blockedBy this user has blocked the viewer (the profile is then not shown at all)
     * @param followedBy this user follows the viewer (what lets the viewer see a private profile, D25b)
     */
    record Relation(boolean following, boolean blocked, boolean blockedBy, boolean followedBy) {}
}
