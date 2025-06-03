package app.soundcheck.catalog.internal;

import static app.soundcheck.shared.jooq.catalog.Tables.ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.SAVED_GIG_FILTER;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** jOOQ access to {@code catalog.saved_gig_filter} and the names the filters show. */
@Repository
class SavedFilterRepository {

    /** A stored filter as it is in the table. */
    record Row(
            UUID id,
            String name,
            List<UUID> artistIds,
            List<UUID> venueIds,
            @Nullable String genre,
            @Nullable String q,
            String place,
            @Nullable Integer cityId) {}

    record VenueName(String name, @Nullable String city) {}

    private final DSLContext dsl;

    SavedFilterRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** One user's filters, oldest first. Expected index: saved_gig_filter_user (user_id, created_at). */
    List<Row> list(UUID userId) {
        return dsl.selectFrom(SAVED_GIG_FILTER)
                .where(SAVED_GIG_FILTER.USER_ID.eq(userId))
                .orderBy(SAVED_GIG_FILTER.CREATED_AT, SAVED_GIG_FILTER.ID)
                .fetch(r -> new Row(
                        r.getId(),
                        r.getName(),
                        List.of(r.getArtistIds()),
                        List.of(r.getVenueIds()),
                        r.getGenre(),
                        r.getQ(),
                        r.getPlace(),
                        r.getCityId()));
    }

    /**
     * Serialises one user's saves until the transaction ends, so "count, then insert" cannot let two
     * saves at the same moment both see 19 and store 21.
     */
    void lockUser(UUID userId) {
        dsl.fetch("select pg_advisory_xact_lock(hashtextextended(?, 0))", "saved-gig-filters:" + userId);
    }

    int count(UUID userId) {
        return dsl.fetchCount(SAVED_GIG_FILTER, SAVED_GIG_FILTER.USER_ID.eq(userId));
    }

    void insert(UUID userId, Row row, Instant now) {
        dsl.insertInto(SAVED_GIG_FILTER)
                .set(SAVED_GIG_FILTER.ID, row.id())
                .set(SAVED_GIG_FILTER.USER_ID, userId)
                .set(SAVED_GIG_FILTER.NAME, row.name())
                .set(SAVED_GIG_FILTER.ARTIST_IDS, row.artistIds().toArray(UUID[]::new))
                .set(SAVED_GIG_FILTER.VENUE_IDS, row.venueIds().toArray(UUID[]::new))
                .set(SAVED_GIG_FILTER.GENRE, row.genre())
                .set(SAVED_GIG_FILTER.Q, row.q())
                .set(SAVED_GIG_FILTER.PLACE, row.place())
                .set(SAVED_GIG_FILTER.CITY_ID, row.cityId())
                .set(SAVED_GIG_FILTER.CREATED_AT, now.atOffset(ZoneOffset.UTC))
                .execute();
    }

    /** Deletes the user's own filter; false when there is no such filter of theirs. */
    boolean delete(UUID userId, UUID id) {
        return dsl.deleteFrom(SAVED_GIG_FILTER)
                        .where(SAVED_GIG_FILTER.ID.eq(id))
                        .and(SAVED_GIG_FILTER.USER_ID.eq(userId))
                        .execute()
                > 0;
    }

    /** Artist id → name, for the ids that exist. */
    Map<UUID, String> artistNames(Collection<UUID> ids) {
        Map<UUID, String> names = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            dsl.select(ARTIST.ID, ARTIST.NAME)
                    .from(ARTIST)
                    .where(ARTIST.ID.in(ids))
                    .forEach(r -> names.put(r.value1(), r.value2()));
        }
        return names;
    }

    /** Venue id → name and town, for the ids that exist. */
    Map<UUID, VenueName> venueNames(Collection<UUID> ids) {
        Map<UUID, VenueName> names = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            dsl.select(VENUE.ID, VENUE.NAME, VENUE.CITY)
                    .from(VENUE)
                    .where(VENUE.ID.in(ids))
                    .forEach(r -> names.put(r.value1(), new VenueName(r.value2(), r.value3())));
        }
        return names;
    }
}
