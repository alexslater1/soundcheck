package app.soundcheck.catalog.internal;

import app.soundcheck.catalog.ArtistFollows;
import app.soundcheck.catalog.GeoPoint;
import app.soundcheck.catalog.ViewerHome;
import app.soundcheck.catalog.ViewerTaste;
import app.soundcheck.catalog.internal.CatalogReadRepository.NameMatch;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The suggestions under the Gigs search box: artists and venues together, ranked first by
 * how well the name matches what was typed and only then by the viewer. A search for "The North"
 * got "North Base" and "North Shared Lounge" above "the north": the normaliser drops a leading "the",
 * all three started with "north", and the tie was broken by name. Now the exact name wins (tier 0–1),
 * and within one tier an act the viewer follows, one playing near them or one of their genres comes
 * before a stranger, then the busier name.
 */
@Service
public class SuggestionService {

    /** At most this many suggestions. */
    static final int LIMIT = 8;

    /** Each kind's pool before re-ranking: enough to hold every exact and whole-word match. */
    static final int POOL = 30;

    /** Personal points: an artist the viewer follows, one playing (or a venue) near home, one of their genres. */
    static final int FOLLOWED = 3;

    static final int NEAR_HOME = 2;
    static final int THEIR_GENRE = 1;

    public enum Kind {
        ARTIST,
        VENUE
    }

    /** One suggestion: an artist or a venue to filter gigs by. */
    public record Suggestion(
            Kind kind,
            UUID id,
            String name,
            @Schema(nullable = true, description = "The venue's town; null for an artist") String city,
            @Schema(nullable = true) String imageUrl,
            int upcomingGigs) {}

    /** What the ranking compares; {@code personal} is the sum of the points above. */
    record Candidate(Suggestion suggestion, int tier, int personal, double similarity) {}

    /** Text match first, then the viewer, then upcoming gigs, then similarity, then name. */
    static final Comparator<Candidate> ORDER = Comparator.comparingInt(Candidate::tier)
            .thenComparing(Comparator.comparingInt(Candidate::personal).reversed())
            .thenComparing(
                    Comparator.comparingInt((Candidate c) -> c.suggestion().upcomingGigs())
                            .reversed())
            .thenComparing(Comparator.comparingDouble(Candidate::similarity).reversed())
            .thenComparing(c -> c.suggestion().name(), String.CASE_INSENSITIVE_ORDER);

    static List<Suggestion> rank(List<Candidate> candidates, int limit) {
        return candidates.stream()
                .sorted(ORDER)
                .limit(limit)
                .map(Candidate::suggestion)
                .toList();
    }

    private final CatalogReadRepository repository;
    private final ViewerHome viewerHome;
    private final ViewerTaste taste;
    private final ArtistFollows follows;
    private final Clock clock;

    SuggestionService(
            CatalogReadRepository repository,
            ViewerHome viewerHome,
            ViewerTaste taste,
            ArtistFollows follows,
            Clock clock) {
        this.repository = repository;
        this.viewerHome = viewerHome;
        this.taste = taste;
        this.follows = follows;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Suggestion> suggest(UUID viewer, String q) {
        Instant now = Instant.now(clock);
        @Nullable GeoPoint home = viewerHome.homeOf(viewer).orElse(null);
        int radius = CatalogService.DEFAULT_RADIUS_KM;
        List<NameMatch> artists = repository.artistMatches(q, now, home, radius, POOL);
        List<NameMatch> venues = repository.venueMatches(q, now, home, radius, POOL);

        List<UUID> artistIds = artists.stream().map(NameMatch::id).toList();
        Set<UUID> followed = follows.followedBy(viewer, artistIds);
        Set<Short> liked = repository.withChildren(taste.genresOf(viewer)).keySet();
        Map<UUID, Map<Short, Double>> genres = repository.genreWeights(artistIds);
        Map<UUID, CatalogReadRepository.ArtistRow> rows = repository.artists(artistIds);

        List<Candidate> candidates = new ArrayList<>();
        for (NameMatch a : artists) {
            int personal = (followed.contains(a.id()) ? FOLLOWED : 0)
                    + (a.nearHome() ? NEAR_HOME : 0)
                    + (genres.getOrDefault(a.id(), Map.of()).keySet().stream().anyMatch(liked::contains)
                            ? THEIR_GENRE
                            : 0);
            CatalogReadRepository.ArtistRow row = rows.get(a.id());
            candidates.add(new Candidate(
                    new Suggestion(
                            Kind.ARTIST, a.id(), a.name(), null, row == null ? null : row.imageUrl(), a.upcoming()),
                    a.tier(),
                    personal,
                    a.similarity()));
        }
        for (NameMatch v : venues) {
            candidates.add(new Candidate(
                    new Suggestion(Kind.VENUE, v.id(), v.name(), v.city(), null, v.upcoming()),
                    v.tier(),
                    v.nearHome() ? NEAR_HOME : 0,
                    v.similarity()));
        }
        return rank(candidates, LIMIT);
    }
}
