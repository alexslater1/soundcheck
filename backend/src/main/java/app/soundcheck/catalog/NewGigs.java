package app.soundcheck.catalog;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the new-gig alert pass (run by social) needs from the catalog: the gigs created recently, and
 * which saved searches each would show up in. Saved searches stay catalog's: a search matches a gig
 * exactly when {@code GET /gigs} with that search would list it.
 */
public interface NewGigs {

    record ArtistRef(UUID id, String name) {}

    record NewGig(
            UUID id,
            String title,
            Instant startsAt,
            String status,
            UUID venueId,
            String venueName,
            @Nullable String venueCity,
            /** The bill, headliner first. */
            List<ArtistRef> artists,
            Instant createdAt,
            /** The fan who added it; null for a source's listing. */
            @Nullable UUID addedBy) {

        /** Worth telling a fan about: still to come, and not cancelled, postponed or unlisted. */
        public boolean alertable(Instant now) {
            return startsAt.isAfter(now) && !NOT_ALERTED.contains(status);
        }

        private static final Set<String> NOT_ALERTED = Set.of("CANCELLED", "POSTPONED", "UNLISTED");
    }

    /** A place in the creation order of gigs: page through {@link #createdSince} after it. */
    record Position(Instant createdAt, UUID gigId) {}

    /** A saved search with alerts on, and a gig it would list. */
    record SearchHit(UUID userId, UUID filterId, String filterName, UUID gigId) {}

    /**
     * Gigs created at or after {@code since}, oldest first (by creation time, then id), at most
     * {@code limit}; after {@code after} when given. One ingestion may create many gigs at the same
     * instant, so paging uses the id too.
     */
    List<NewGig> createdSince(Instant since, @Nullable Position after, int limit);

    /** Every (saved search with alerts on, gig) pair among these gigs. */
    List<SearchHit> savedSearchHits(Collection<UUID> gigIds);
}
