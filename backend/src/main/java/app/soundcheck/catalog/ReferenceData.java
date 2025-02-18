package app.soundcheck.catalog;

import java.util.List;
import java.util.Optional;

/** Public API of the catalog module for reference data: genres and cities (used by identity for onboarding). */
public interface ReferenceData {

    /** All canonical genres, ordered by id. */
    List<Genre> genres();

    Optional<Genre> genre(short id);

    /** Cities whose name starts with {@code query} (case- and accent-insensitive), most populous first. */
    List<City> searchCities(String query, int limit);

    Optional<City> city(int id);
}
