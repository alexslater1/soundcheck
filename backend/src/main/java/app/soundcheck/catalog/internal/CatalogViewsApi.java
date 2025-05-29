package app.soundcheck.catalog.internal;

import app.soundcheck.catalog.ArtistSummary;
import app.soundcheck.catalog.CatalogViews;
import app.soundcheck.catalog.GigSummary;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** {@link CatalogViews} for other modules; the summaries are assembled by {@link CatalogService}. */
@Component
class CatalogViewsApi implements CatalogViews {

    private final CatalogService catalog;

    CatalogViewsApi(CatalogService catalog) {
        this.catalog = catalog;
    }

    @Override
    public Map<UUID, GigSummary> gigs(UUID viewerId, Collection<UUID> gigIds) {
        return catalog.gigsByIds(viewerId, gigIds);
    }

    @Override
    public Map<UUID, ArtistSummary> artists(UUID viewerId, Collection<UUID> artistIds) {
        return catalog.artistsByIds(viewerId, artistIds);
    }

    @Override
    public List<UUID> lineup(UUID gigId) {
        return catalog.lineup(gigId);
    }

    @Override
    public Map<UUID, Map<Short, Double>> genreWeights(Collection<UUID> artistIds) {
        return catalog.genreWeights(artistIds);
    }

    @Override
    public Map<UUID, String> attendanceOf(UUID userId) {
        return catalog.attendanceOf(userId);
    }

    @Override
    public boolean venueExists(UUID venueId) {
        return catalog.venueExists(venueId);
    }

    @Override
    public List<UUID> gigIdsAtVenue(UUID venueId, int limit) {
        return catalog.gigIdsAtVenue(venueId, limit);
    }
}
