package app.soundcheck.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Public API of the catalog module for reference data: genres and cities (used by identity for onboarding). */
public interface ReferenceData {

    /** All canonical genres, ordered by id. */
    List<Genre> genres();

    Optional<Genre> genre(short id);

    /**
     * Other names of each genre, lower case (Last.fm tags such as "dnb" or "shoegaze"), strongest match
     * first; the genre's own name is not repeated. The sign-up genre search matches them.
     */
    Map<Short, List<String>> genreAliases();

    /** Cities whose name starts with {@code query} (case- and accent-insensitive), most populous first. */
    List<City> searchCities(String query, int limit);

    Optional<City> city(int id);
}
