package app.soundcheck.feed.internal;

import app.soundcheck.catalog.GeoPoint;
import app.soundcheck.feed.internal.ranking.FeatureComputer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Everything ranking needs to know about the viewer, loaded once per feed session.
 *
 * @param attendance  gig id → INTERESTED, GOING or WENT
 * @param genreVector the blended taste vector (chosen genres 0.6, engagement 0.4), length 1
 * @param topGenres   its three strongest genres (the TASTE candidates)
 * @param home        the centroid of the viewer's home city, if they chose one
 * @param blocked     everyone with a block between them and the viewer, either way
 * @param hidden      posts the viewer asked to see less of
 * @param seen        posts shown to the viewer in the last few days
 */
public record UserContext(
        UUID userId,
        Set<UUID> followedUsers,
        Set<UUID> followedArtists,
        Map<UUID, String> attendance,
        Map<Short, Double> genreVector,
        List<Short> topGenres,
        @Nullable GeoPoint home,
        Set<UUID> blocked,
        Set<UUID> hidden,
        Set<UUID> seen,
        Map<UUID, Double> artistAffinity,
        Map<UUID, Double> authorAffinity) {

    /** The part of the context the feature formulas use. */
    public FeatureComputer.Viewer viewer() {
        return new FeatureComputer.Viewer(
                followedUsers, followedArtists, attendance, genreVector, seen, artistAffinity, authorAffinity);
    }
}
