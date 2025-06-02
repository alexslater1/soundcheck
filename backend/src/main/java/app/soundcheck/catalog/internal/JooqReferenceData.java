package app.soundcheck.catalog.internal;

import static app.soundcheck.shared.jooq.catalog.Tables.CITY;
import static app.soundcheck.shared.jooq.catalog.Tables.GENRE;
import static app.soundcheck.shared.jooq.catalog.Tables.GENRE_ALIAS;

import app.soundcheck.catalog.City;
import app.soundcheck.catalog.Genre;
import app.soundcheck.catalog.ReferenceData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** jOOQ implementation of {@link ReferenceData} over {@code catalog.genre} and {@code catalog.city}. */
@Repository
@Transactional(readOnly = true)
class JooqReferenceData implements ReferenceData {

    /** Centroid coordinates read back from the PostGIS geography column. */
    static final Field<Double> CITY_LAT = DSL.field("ST_Y({0}::geometry)", Double.class, CITY.LOCATION);

    static final Field<Double> CITY_LNG = DSL.field("ST_X({0}::geometry)", Double.class, CITY.LOCATION);

    private final DSLContext dsl;

    JooqReferenceData(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<Genre> genres() {
        return dsl.select(GENRE.ID, GENRE.SLUG, GENRE.NAME, GENRE.PARENT_ID)
                .from(GENRE)
                .orderBy(GENRE.ID)
                .fetch(JooqReferenceData::toGenre);
    }

    @Override
    public Map<Short, List<String>> genreAliases() {
        Map<Short, List<String>> byGenre = new LinkedHashMap<>();
        dsl.select(GENRE_ALIAS.GENRE_ID, GENRE_ALIAS.ALIAS, GENRE.NAME)
                .from(GENRE_ALIAS)
                .join(GENRE)
                .on(GENRE.ID.eq(GENRE_ALIAS.GENRE_ID))
                .orderBy(GENRE_ALIAS.GENRE_ID, GENRE_ALIAS.WEIGHT.desc(), GENRE_ALIAS.ALIAS)
                .forEach(r -> {
                    String alias = r.value2().toLowerCase(Locale.ROOT);
                    List<String> aliases = byGenre.computeIfAbsent(r.value1(), id -> new ArrayList<>());
                    // Ticketmaster's names repeat the genre's own ("Rock") or differ only in case.
                    if (!alias.equals(r.value3().toLowerCase(Locale.ROOT)) && !aliases.contains(alias)) {
                        aliases.add(alias);
                    }
                });
        return byGenre;
    }

    @Override
    public Optional<Genre> genre(short id) {
        return dsl.select(GENRE.ID, GENRE.SLUG, GENRE.NAME, GENRE.PARENT_ID)
                .from(GENRE)
                .where(GENRE.ID.eq(id))
                .fetchOptional(JooqReferenceData::toGenre);
    }

    @Override
    public List<City> searchCities(String query, int limit) {
        String prefix = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        // Expected index: city_name_prefix (lower(name) text_pattern_ops) — prefix LIKE is index-assisted.
        Condition match =
                prefix.isEmpty() ? DSL.noCondition() : DSL.lower(CITY.NAME).like(DSL.escape(prefix, '!') + "%", '!');
        return dsl.select(CITY.ID, CITY.NAME, CITY.REGION, CITY_LAT, CITY_LNG, CITY.POPULATION)
                .from(CITY)
                .where(match)
                .orderBy(CITY.POPULATION.desc().nullsLast(), CITY.NAME.asc())
                .limit(limit)
                .fetch(JooqReferenceData::toCity);
    }

    @Override
    public Optional<City> city(int id) {
        return dsl.select(CITY.ID, CITY.NAME, CITY.REGION, CITY_LAT, CITY_LNG, CITY.POPULATION)
                .from(CITY)
                .where(CITY.ID.eq(id))
                .fetchOptional(JooqReferenceData::toCity);
    }

    private static Genre toGenre(Record r) {
        return new Genre(r.get(GENRE.ID), r.get(GENRE.SLUG), r.get(GENRE.NAME), r.get(GENRE.PARENT_ID));
    }

    private static City toCity(Record r) {
        return new City(
                r.get(CITY.ID),
                r.get(CITY.NAME),
                r.get(CITY.REGION),
                r.get(CITY_LAT),
                r.get(CITY_LNG),
                r.get(CITY.POPULATION));
    }
}
