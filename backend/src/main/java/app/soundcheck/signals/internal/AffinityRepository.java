package app.soundcheck.signals.internal;

import static app.soundcheck.shared.jooq.signals.Tables.USER_ARTIST_AFFINITY;
import static app.soundcheck.shared.jooq.signals.Tables.USER_AUTHOR_AFFINITY;
import static app.soundcheck.shared.jooq.signals.Tables.USER_GENRE_AFFINITY;
import static org.jooq.impl.DSL.excluded;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** The three affinity tables: user → artist, user → author, user → genre. */
@Repository
class AffinityRepository {

    /** A stored score and when it was written (decay is counted from then). */
    record Stored(double score, Instant updatedAt) {}

    /** user id + the id of the artist, author or genre. */
    record Key<T>(UUID userId, T target) {}

    private final DSLContext dsl;

    AffinityRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ── artists ──────────────────────────────────────────────────────────────────────────────────

    Map<Key<UUID>, Stored> artistScores(Collection<UUID> userIds) {
        Map<Key<UUID>, Stored> rows = new HashMap<>();
        if (!userIds.isEmpty()) {
            dsl.selectFrom(USER_ARTIST_AFFINITY)
                    .where(USER_ARTIST_AFFINITY.USER_ID.in(userIds))
                    .forEach(r -> rows.put(
                            new Key<>(r.getUserId(), r.getArtistId()),
                            new Stored(r.getScore(), r.getUpdatedAt().toInstant())));
        }
        return rows;
    }

    void saveArtistScores(Map<Key<UUID>, Double> scores, Instant now) {
        if (scores.isEmpty()) {
            return;
        }
        var insert = dsl.insertInto(
                USER_ARTIST_AFFINITY,
                USER_ARTIST_AFFINITY.USER_ID,
                USER_ARTIST_AFFINITY.ARTIST_ID,
                USER_ARTIST_AFFINITY.SCORE,
                USER_ARTIST_AFFINITY.UPDATED_AT);
        for (var entry : scores.entrySet()) {
            insert = insert.values(
                    entry.getKey().userId(),
                    entry.getKey().target(),
                    entry.getValue().floatValue(),
                    at(now));
        }
        insert.onConflict(USER_ARTIST_AFFINITY.USER_ID, USER_ARTIST_AFFINITY.ARTIST_ID)
                .doUpdate()
                .set(USER_ARTIST_AFFINITY.SCORE, excluded(USER_ARTIST_AFFINITY.SCORE))
                .set(USER_ARTIST_AFFINITY.UPDATED_AT, excluded(USER_ARTIST_AFFINITY.UPDATED_AT))
                .execute();
    }

    /** Expected index: the primary key (user_id, artist_id). */
    Map<UUID, Stored> artistsOf(UUID userId) {
        Map<UUID, Stored> rows = new HashMap<>();
        dsl.selectFrom(USER_ARTIST_AFFINITY)
                .where(USER_ARTIST_AFFINITY.USER_ID.eq(userId))
                .forEach(r -> rows.put(
                        r.getArtistId(),
                        new Stored(r.getScore(), r.getUpdatedAt().toInstant())));
        return rows;
    }

    // ── authors ──────────────────────────────────────────────────────────────────────────────────

    Map<Key<UUID>, Stored> authorScores(Collection<UUID> userIds) {
        Map<Key<UUID>, Stored> rows = new HashMap<>();
        if (!userIds.isEmpty()) {
            dsl.selectFrom(USER_AUTHOR_AFFINITY)
                    .where(USER_AUTHOR_AFFINITY.USER_ID.in(userIds))
                    .forEach(r -> rows.put(
                            new Key<>(r.getUserId(), r.getAuthorId()),
                            new Stored(r.getScore(), r.getUpdatedAt().toInstant())));
        }
        return rows;
    }

    void saveAuthorScores(Map<Key<UUID>, Double> scores, Instant now) {
        if (scores.isEmpty()) {
            return;
        }
        var insert = dsl.insertInto(
                USER_AUTHOR_AFFINITY,
                USER_AUTHOR_AFFINITY.USER_ID,
                USER_AUTHOR_AFFINITY.AUTHOR_ID,
                USER_AUTHOR_AFFINITY.SCORE,
                USER_AUTHOR_AFFINITY.UPDATED_AT);
        for (var entry : scores.entrySet()) {
            insert = insert.values(
                    entry.getKey().userId(),
                    entry.getKey().target(),
                    entry.getValue().floatValue(),
                    at(now));
        }
        insert.onConflict(USER_AUTHOR_AFFINITY.USER_ID, USER_AUTHOR_AFFINITY.AUTHOR_ID)
                .doUpdate()
                .set(USER_AUTHOR_AFFINITY.SCORE, excluded(USER_AUTHOR_AFFINITY.SCORE))
                .set(USER_AUTHOR_AFFINITY.UPDATED_AT, excluded(USER_AUTHOR_AFFINITY.UPDATED_AT))
                .execute();
    }

    Map<UUID, Stored> authorsOf(UUID userId) {
        Map<UUID, Stored> rows = new HashMap<>();
        dsl.selectFrom(USER_AUTHOR_AFFINITY)
                .where(USER_AUTHOR_AFFINITY.USER_ID.eq(userId))
                .forEach(r -> rows.put(
                        r.getAuthorId(),
                        new Stored(r.getScore(), r.getUpdatedAt().toInstant())));
        return rows;
    }

    // ── genres ───────────────────────────────────────────────────────────────────────────────────

    Map<Key<Short>, Stored> genreScores(Collection<UUID> userIds) {
        Map<Key<Short>, Stored> rows = new HashMap<>();
        if (!userIds.isEmpty()) {
            dsl.selectFrom(USER_GENRE_AFFINITY)
                    .where(USER_GENRE_AFFINITY.USER_ID.in(userIds))
                    .forEach(r -> rows.put(
                            new Key<>(r.getUserId(), r.getGenreId()),
                            new Stored(r.getScore(), r.getUpdatedAt().toInstant())));
        }
        return rows;
    }

    void saveGenreScores(Map<Key<Short>, Double> scores, Instant now) {
        if (scores.isEmpty()) {
            return;
        }
        var insert = dsl.insertInto(
                USER_GENRE_AFFINITY,
                USER_GENRE_AFFINITY.USER_ID,
                USER_GENRE_AFFINITY.GENRE_ID,
                USER_GENRE_AFFINITY.SCORE,
                USER_GENRE_AFFINITY.UPDATED_AT);
        for (var entry : scores.entrySet()) {
            insert = insert.values(
                    entry.getKey().userId(),
                    entry.getKey().target(),
                    entry.getValue().floatValue(),
                    at(now));
        }
        insert.onConflict(USER_GENRE_AFFINITY.USER_ID, USER_GENRE_AFFINITY.GENRE_ID)
                .doUpdate()
                .set(USER_GENRE_AFFINITY.SCORE, excluded(USER_GENRE_AFFINITY.SCORE))
                .set(USER_GENRE_AFFINITY.UPDATED_AT, excluded(USER_GENRE_AFFINITY.UPDATED_AT))
                .execute();
    }

    Map<Short, Stored> genresOf(UUID userId) {
        Map<Short, Stored> rows = new HashMap<>();
        dsl.selectFrom(USER_GENRE_AFFINITY)
                .where(USER_GENRE_AFFINITY.USER_ID.eq(userId))
                .forEach(r -> rows.put(
                        r.getGenreId(),
                        new Stored(r.getScore(), r.getUpdatedAt().toInstant())));
        return rows;
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** Splits a list for {@code IN (…)} lookups. */
    static <T> List<List<T>> chunks(List<T> items, int size) {
        List<List<T>> chunks = new java.util.ArrayList<>();
        for (int from = 0; from < items.size(); from += size) {
            chunks.add(items.subList(from, Math.min(items.size(), from + size)));
        }
        return chunks;
    }
}
