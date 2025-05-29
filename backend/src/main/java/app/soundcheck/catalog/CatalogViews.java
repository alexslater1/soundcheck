package app.soundcheck.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Catalog's summaries for other modules: a post shows the gig it was filmed at and the artists tagged
 * in it. Batch methods take many ids, so a page of posts costs a fixed number of queries.
 * (Separate from {@link Catalog} because the bean behind this one needs social's follow lookup, and
 * social needs {@code Catalog}: one bean for both would need itself to be constructed.)
 */
public interface CatalogViews {

    /** Summaries of the gigs that exist among the ids, with the viewer's attendance. */
    Map<UUID, GigSummary> gigs(UUID viewerId, Collection<UUID> gigIds);

    /** Summaries of the artists that exist among the ids, with whether the viewer follows them. */
    Map<UUID, ArtistSummary> artists(UUID viewerId, Collection<UUID> artistIds);

    /** A gig's bill as artist ids, headliner first; empty when the gig does not exist. */
    List<UUID> lineup(UUID gigId);

    /**
     * Each artist's genres as genre id → weight (the weights of one artist sum to at most 1); artists
     * without genres are absent. What a post "sounds like" is the mean over its tagged artists.
     */
    Map<UUID, Map<Short, Double>> genreWeights(Collection<UUID> artistIds);

    boolean venueExists(UUID venueId);

    /** Every gig at a venue (ids only), newest first, at most {@code limit}. */
    List<UUID> gigIdsAtVenue(UUID venueId, int limit);
}
