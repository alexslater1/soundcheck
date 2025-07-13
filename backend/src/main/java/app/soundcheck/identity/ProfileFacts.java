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
     * @param following the viewer follows this user (what lets the viewer see a private profile)
     * @param requested the viewer has asked to follow this private profile and is waiting
     * @param blocked the viewer has blocked this user
     * @param blockedBy this user has blocked the viewer (the profile is then not shown at all)
     * @param hiddenByPrivacy the profile is private and the viewer is neither its owner nor a follower:
     *     social's {@code ProfileAccess} answers it, so identity applies the same rule as every other read
     */
    record Relation(
            boolean following, boolean requested, boolean blocked, boolean blockedBy, boolean hiddenByPrivacy) {}
}
