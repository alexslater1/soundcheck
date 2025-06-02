package app.soundcheck.catalog.internal;

import app.soundcheck.catalog.City;
import app.soundcheck.catalog.ReferenceData;
import app.soundcheck.shared.Ids;
import app.soundcheck.shared.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saved Gigs-tab searches, which keep "Keo, Bleech 9:3 and The North in London"
 * one tap away. A filter is exactly what the Gigs tab sends to {@code GET /gigs}: acts, venues, a genre,
 * a text, and a place (near home, UK-wide, or a city). It stores ids; names are looked up when the list
 * is read, and an act or venue that no longer exists is left out.
 */
@Service
public class SavedFilterService {

    /** A fan can keep this many. */
    static final int MAX_PER_USER = 20;

    public enum PlaceKind {
        HOME,
        UK,
        CITY
    }

    public record NamedArtist(UUID id, String name) {}

    public record NamedVenue(UUID id, String name, @Schema(nullable = true) String city) {}

    /** Where to look: home (the user's city), the whole UK, or one city (with its centre). */
    public record Place(
            PlaceKind kind,
            @Schema(nullable = true) Integer cityId,
            @Schema(nullable = true) String cityName,
            @Schema(nullable = true) Double lat,
            @Schema(nullable = true) Double lng) {}

    public record SavedGigFilter(
            UUID id,
            String name,
            List<NamedArtist> artists,
            List<NamedVenue> venues,
            @Schema(nullable = true) String genre,
            @Schema(nullable = true) String q,
            Place place) {}

    /** A filter to save, as validated by the controller. */
    public record NewFilter(
            String name,
            List<UUID> artistIds,
            List<UUID> venueIds,
            @Nullable String genre,
            @Nullable String q,
            PlaceKind place,
            @Nullable Integer cityId) {}

    private final SavedFilterRepository filters;
    private final CatalogReadRepository catalog;
    private final ReferenceData reference;
    private final Clock clock;

    SavedFilterService(
            SavedFilterRepository filters, CatalogReadRepository catalog, ReferenceData reference, Clock clock) {
        this.filters = filters;
        this.catalog = catalog;
        this.reference = reference;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SavedGigFilter> list(UUID userId) {
        List<SavedFilterRepository.Row> rows = filters.list(userId);
        Set<UUID> artistIds = new HashSet<>();
        Set<UUID> venueIds = new HashSet<>();
        rows.forEach(r -> {
            artistIds.addAll(r.artistIds());
            venueIds.addAll(r.venueIds());
        });
        Map<UUID, String> artists = filters.artistNames(artistIds);
        Map<UUID, SavedFilterRepository.VenueName> venues = filters.venueNames(venueIds);
        List<SavedGigFilter> out = new ArrayList<>();
        for (SavedFilterRepository.Row row : rows) {
            out.add(new SavedGigFilter(
                    row.id(),
                    row.name(),
                    row.artistIds().stream()
                            .filter(artists::containsKey)
                            .map(id -> new NamedArtist(id, artists.get(id)))
                            .toList(),
                    row.venueIds().stream()
                            .filter(venues::containsKey)
                            .map(id -> new NamedVenue(
                                    id, venues.get(id).name(), venues.get(id).city()))
                            .toList(),
                    row.genre(),
                    row.q(),
                    place(PlaceKind.valueOf(row.place()), row.cityId())));
        }
        return out;
    }

    @Transactional
    public SavedGigFilter save(UUID userId, NewFilter filter) {
        if (filters.count(userId) >= MAX_PER_USER) {
            throw ApiException.conflict(
                    "SAVED_FILTERS_FULL", "At most " + MAX_PER_USER + " saved searches; delete one first");
        }
        List<UUID> artistIds = List.copyOf(new LinkedHashSet<>(filter.artistIds()));
        List<UUID> venueIds = List.copyOf(new LinkedHashSet<>(filter.venueIds()));
        if (filters.artistNames(artistIds).size() != artistIds.size()) {
            throw ApiException.badRequest("ARTIST_UNKNOWN", "Unknown artist in artistIds");
        }
        if (filters.venueNames(venueIds).size() != venueIds.size()) {
            throw ApiException.badRequest("VENUE_UNKNOWN", "Unknown venue in venueIds");
        }
        String genre = blankToNull(filter.genre());
        if (genre != null && catalog.genreWithChildren(genre).isEmpty()) {
            throw ApiException.badRequest("GENRE_UNKNOWN", "Unknown genre " + genre);
        }
        Integer cityId = filter.place() == PlaceKind.CITY ? filter.cityId() : null;
        if (filter.place() == PlaceKind.CITY && cityId == null) {
            throw ApiException.badRequest("CITY_REQUIRED", "A filter for a city needs cityId");
        }
        place(filter.place(), cityId); // refuses an unknown city before anything is stored
        UUID id = Ids.newId();
        filters.insert(
                userId,
                new SavedFilterRepository.Row(
                        id,
                        filter.name().strip(),
                        artistIds,
                        venueIds,
                        genre,
                        blankToNull(filter.q()),
                        filter.place().name(),
                        cityId),
                Instant.now(clock));
        return list(userId).stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        if (!filters.delete(userId, id)) {
            throw ApiException.notFound("SAVED_FILTER_NOT_FOUND", "No saved search " + id);
        }
    }

    private Place place(PlaceKind kind, @Nullable Integer cityId) {
        if (kind != PlaceKind.CITY || cityId == null) {
            return new Place(kind, null, null, null, null);
        }
        City city = reference
                .city(cityId)
                .orElseThrow(() -> ApiException.badRequest("CITY_UNKNOWN", "Unknown city " + cityId));
        return new Place(kind, city.id(), city.name(), city.lat(), city.lng());
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
