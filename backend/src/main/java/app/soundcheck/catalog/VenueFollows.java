package app.soundcheck.catalog;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * What the catalog needs to know about venue follows to show "Following" and a follower count on a
 * venue page. Declared here and implemented by social, which owns {@code social.venue_follow}, like
 * {@link ArtistFollows}: the dependency keeps pointing social → catalog.
 */
public interface VenueFollows {

    /** The subset of {@code venueIds} the user follows. */
    Set<UUID> followedBy(UUID userId, Collection<UUID> venueIds);

    long followerCount(UUID venueId);
}
