package app.soundcheck.catalog.internal;

import app.soundcheck.catalog.Catalog;
import app.soundcheck.catalog.GigFacts;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catalog's read API for other modules. A separate bean from {@link CatalogService} on purpose: social's
 * follow service needs "does this artist exist?" from catalog, while {@code CatalogService} needs "who
 * follows whom?" from social — as one bean each, the two would need each other to be constructed.
 */
@Component
@Transactional(readOnly = true)
class CatalogLookup implements Catalog {

    private final CatalogReadRepository repository;

    CatalogLookup(CatalogReadRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean artistExists(UUID artistId) {
        return repository.artistExists(artistId);
    }

    @Override
    public boolean gigExists(UUID gigId) {
        return repository.gigExists(gigId);
    }

    @Override
    public boolean venueExists(UUID venueId) {
        return repository.venueExists(venueId);
    }

    @Override
    public Optional<GigFacts> gigFacts(UUID gigId) {
        return repository.gig(gigId, null).map(row -> {
            List<UUID> lineup = repository.lineups(List.of(gigId)).stream()
                    .sorted(Comparator.comparingInt(CatalogReadRepository.LineupEntry::billingOrder))
                    .map(CatalogReadRepository.LineupEntry::artistId)
                    .toList();
            Map<UUID, CatalogReadRepository.ArtistRow> artists = repository.artists(lineup);
            List<GigFacts.Act> acts = lineup.stream()
                    .filter(artists::containsKey)
                    .map(id -> new GigFacts.Act(
                            id, artists.get(id).name(), artists.get(id).mbid()))
                    .toList();
            return new GigFacts(
                    row.id(),
                    row.startsAt(),
                    row.venueName(),
                    row.venueCity(),
                    acts,
                    repository.externalId(gigId, "SETLISTFM").orElse(null));
        });
    }
}
