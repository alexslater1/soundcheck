package app.soundcheck.catalog;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What the catalog needs to know about follows to fill {@code ArtistSummary.isFollowing} and follower
 * counts. Declared here, implemented by the social module (which owns {@code social.artist_follow}), so
 * the dependency keeps pointing social → catalog to keep module boundaries.
 */
public interface ArtistFollows {

    /** The subset of {@code artistIds} the user follows. */
    Set<UUID> followedBy(UUID userId, Collection<UUID> artistIds);

    long followerCount(UUID artistId);

    /** How much the user hears about the artist (ALL, POSTS, GIGS or NONE); empty when not following. */
    Optional<String> notifyLevel(UUID userId, UUID artistId);
}
