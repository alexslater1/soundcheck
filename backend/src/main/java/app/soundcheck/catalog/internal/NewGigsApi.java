package app.soundcheck.catalog.internal;

import app.soundcheck.catalog.City;
import app.soundcheck.catalog.GeoPoint;
import app.soundcheck.catalog.NewGigs;
import app.soundcheck.catalog.ReferenceData;
import app.soundcheck.catalog.ViewerHome;
import app.soundcheck.catalog.internal.CatalogReadRepository.CreatedGig;
import app.soundcheck.catalog.internal.CatalogReadRepository.GigFilter;
import app.soundcheck.catalog.internal.CatalogReadRepository.LineupEntry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Catalog's side of the new-gig alerts: recent gigs, and the saved searches they match. */
@Component
@Transactional(readOnly = true)
class NewGigsApi implements NewGigs {

    private final CatalogReadRepository repository;
    private final SavedFilterRepository filters;
    private final ViewerHome viewerHome;
    private final ReferenceData reference;
    private final Clock clock;

    NewGigsApi(
            CatalogReadRepository repository,
            SavedFilterRepository filters,
            ViewerHome viewerHome,
            ReferenceData reference,
            Clock clock) {
        this.repository = repository;
        this.filters = filters;
        this.viewerHome = viewerHome;
        this.reference = reference;
        this.clock = clock;
    }

    @Override
    public List<NewGig> createdSince(Instant since, @Nullable Position after, int limit) {
        List<CreatedGig> rows = repository.createdSince(
                since, after == null ? null : after.createdAt(), after == null ? null : after.gigId(), limit);
        Map<UUID, List<UUID>> bills = new LinkedHashMap<>();
        for (LineupEntry entry :
                repository.lineups(rows.stream().map(CreatedGig::id).toList())) {
            bills.computeIfAbsent(entry.gigId(), k -> new ArrayList<>()).add(entry.artistId());
        }
        Map<UUID, CatalogReadRepository.ArtistRow> artists = repository.artists(
                bills.values().stream().flatMap(List::stream).distinct().toList());
        return rows.stream()
                .map(g -> new NewGig(
                        g.id(),
                        g.title(),
                        g.startsAt(),
                        g.status(),
                        g.venueId(),
                        g.venueName(),
                        g.venueCity(),
                        bills.getOrDefault(g.id(), List.of()).stream()
                                .filter(artists::containsKey)
                                .map(id -> new ArtistRef(id, artists.get(id).name()))
                                .toList(),
                        g.createdAt(),
                        g.addedBy()))
                .toList();
    }

    /**
     * One query per saved search with alerts on, each narrowed to the given gigs by primary key: fine for
     * the demo's few hundred searches; a larger service would match in bulk (a known gap).
     */
    @Override
    public List<SearchHit> savedSearchHits(Collection<UUID> gigIds) {
        if (gigIds.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now(clock);
        Map<UUID, Optional<GeoPoint>> homes = new HashMap<>();
        List<SearchHit> hits = new ArrayList<>();
        for (SavedFilterRepository.OwnedRow owned : filters.withAlerts()) {
            SavedFilterRepository.Row f = owned.row();
            GeoPoint origin = originOf(f, owned.userId(), homes);
            boolean nationwide = SavedFilterService.PlaceKind.UK.name().equals(f.place());
            Set<Short> genreIds = null;
            if (f.genre() != null) {
                genreIds = repository.genreWithChildren(f.genre());
                if (genreIds.isEmpty()) {
                    continue; // a genre that no longer exists matches nothing, as GET /gigs would refuse it
                }
            }
            GigFilter filter = new GigFilter(
                    nationwide ? null : origin,
                    nationwide || origin == null ? null : CatalogService.DEFAULT_RADIUS_KM,
                    now,
                    null,
                    genreIds,
                    f.q(),
                    f.artistIds().isEmpty() ? null : Set.copyOf(f.artistIds()),
                    f.venueIds().isEmpty() ? null : Set.copyOf(f.venueIds()),
                    null,
                    null,
                    false,
                    null,
                    1);
            for (UUID gigId : repository.matching(filter, gigIds)) {
                hits.add(new SearchHit(owned.userId(), f.id(), f.name(), gigId));
            }
        }
        return hits;
    }

    /** Where a saved search looks from, as the Gigs tab does: home, a city's centre, or nowhere (UK-wide). */
    private @Nullable GeoPoint originOf(SavedFilterRepository.Row f, UUID userId, Map<UUID, Optional<GeoPoint>> homes) {
        return switch (SavedFilterService.PlaceKind.valueOf(f.place())) {
            case HOME -> homes.computeIfAbsent(userId, viewerHome::homeOf).orElse(null);
            case UK -> null;
            case CITY ->
                f.cityId() == null
                        ? null
                        : reference.city(f.cityId()).map(NewGigsApi::centre).orElse(null);
        };
    }

    private static GeoPoint centre(City city) {
        return new GeoPoint(city.lat(), city.lng());
    }
}
