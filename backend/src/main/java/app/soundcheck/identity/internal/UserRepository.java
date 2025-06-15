package app.soundcheck.identity.internal;

import static app.soundcheck.shared.jooq.identity.Tables.APP_USER;
import static app.soundcheck.shared.jooq.identity.Tables.USER_GENRE_PREFERENCE;

import app.soundcheck.catalog.GeoPoint;
import app.soundcheck.shared.web.ApiException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/** jOOQ access to {@code identity.app_user} and {@code identity.user_genre_preference}. */
@Repository
public class UserRepository {

    /** One explicit genre preference. */
    public record GenrePreference(short genreId, float weight) {}

    /** What a user summary is built from. */
    public record SummaryRow(UUID id, String handle, String displayName, @Nullable UUID avatarMediaId) {}

    private final DSLContext dsl;

    public UserRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** The names PostgreSQL gave the {@code unique} column constraints in V002. */
    static final String EMAIL_UNIQUE = "app_user_email_key";

    static final String HANDLE_UNIQUE = "app_user_handle_key";

    /**
     * @throws ApiException 409 {@code EMAIL_TAKEN} / {@code HANDLE_TAKEN} when another request took the
     *     email or handle between the caller's existence check and this insert
     */
    public void insert(UserRecord user) {
        try {
            dsl.insertInto(APP_USER)
                    .set(APP_USER.ID, user.id())
                    .set(APP_USER.EMAIL, user.email())
                    .set(APP_USER.HANDLE, user.handle())
                    .set(APP_USER.DISPLAY_NAME, user.displayName())
                    .set(APP_USER.BIO, user.bio())
                    .set(APP_USER.ROLE, user.role())
                    .set(APP_USER.PASSWORD_HASH, user.passwordHash())
                    .set(APP_USER.HOME_CITY_ID, user.homeCityId())
                    .set(APP_USER.CREATED_AT, at(user.createdAt()))
                    .set(APP_USER.UPDATED_AT, at(user.createdAt()))
                    .execute();
        } catch (DataIntegrityViolationException e) {
            throw taken(e);
        }
    }

    /**
     * The existence checks in the services give the friendly error in the common case, but two requests
     * can both pass them; the unique constraint is what actually decides. Its violation becomes the same
     * 409 instead of a 500. Anything else (a check constraint, a foreign key) is rethrown untouched.
     */
    private static RuntimeException taken(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains(EMAIL_UNIQUE)) {
            return ApiException.conflict("EMAIL_TAKEN", "An account with that email already exists");
        }
        if (message.contains(HANDLE_UNIQUE)) {
            return ApiException.conflict("HANDLE_TAKEN", "That handle is taken");
        }
        return e;
    }

    public Optional<UserRecord> findById(UUID id) {
        return dsl.selectFrom(APP_USER).where(APP_USER.ID.eq(id)).fetchOptional(UserRepository::toRecord);
    }

    /** Case-insensitive ({@code citext}). */
    public Optional<UserRecord> findByEmail(String email) {
        return dsl.selectFrom(APP_USER).where(APP_USER.EMAIL.eq(email)).fetchOptional(UserRepository::toRecord);
    }

    public Optional<UserRecord> findByHandle(String handle) {
        return dsl.selectFrom(APP_USER).where(APP_USER.HANDLE.eq(handle)).fetchOptional(UserRepository::toRecord);
    }

    /** Live users among the ids. Expected index: the primary key. */
    public List<SummaryRow> summaries(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return dsl.select(APP_USER.ID, APP_USER.HANDLE, APP_USER.DISPLAY_NAME, APP_USER.AVATAR_MEDIA_ID)
                .from(APP_USER)
                .where(APP_USER.ID.in(ids))
                .and(APP_USER.DELETED_AT.isNull())
                .fetch(r -> new SummaryRow(r.value1(), r.value2(), r.value3(), r.value4()));
    }

    public void setAvatar(UUID id, UUID mediaId, Instant now) {
        dsl.update(APP_USER)
                .set(APP_USER.AVATAR_MEDIA_ID, mediaId)
                .set(APP_USER.UPDATED_AT, at(now))
                .where(APP_USER.ID.eq(id))
                .execute();
    }

    public void setShowGigStats(UUID id, boolean show, Instant now) {
        dsl.update(APP_USER)
                .set(APP_USER.SHOW_GIG_STATS, show)
                .set(APP_USER.UPDATED_AT, at(now))
                .where(APP_USER.ID.eq(id))
                .execute();
    }

    public boolean emailExists(String email) {
        return dsl.fetchExists(APP_USER, APP_USER.EMAIL.eq(email));
    }

    public boolean handleExists(String handle) {
        return dsl.fetchExists(APP_USER, APP_USER.HANDLE.eq(handle));
    }

    /**
     * Profile fields; a null {@code homeCity} leaves the city untouched.
     *
     * @throws ApiException 409 {@code HANDLE_TAKEN} when a concurrent request took the handle first
     */
    public void updateProfile(
            UUID id,
            String displayName,
            @Nullable String bio,
            String handle,
            @Nullable Integer homeCityId,
            @Nullable Double homeLat,
            @Nullable Double homeLng,
            Instant now) {
        var update = dsl.update(APP_USER)
                .set(APP_USER.DISPLAY_NAME, displayName)
                .set(APP_USER.BIO, bio)
                .set(APP_USER.HANDLE, handle)
                .set(APP_USER.UPDATED_AT, at(now));
        if (homeCityId != null) {
            // Map form avoids the set(Field, Object) / set(Field, Field) overload ambiguity on the geography column.
            update = update.set(APP_USER.HOME_CITY_ID, homeCityId)
                    .set(Map.of(
                            APP_USER.HOME_LOCATION,
                            DSL.field(
                                    "ST_SetSRID(ST_MakePoint({0}, {1}), 4326)::geography",
                                    Object.class, DSL.val(homeLng), DSL.val(homeLat))));
        }
        try {
            update.where(APP_USER.ID.eq(id)).execute();
        } catch (DataIntegrityViolationException e) {
            throw taken(e);
        }
    }

    /** The stored home-city centroid of a live user, if they picked a city. */
    public Optional<GeoPoint> homeLocation(UUID id) {
        return dsl.select(
                        DSL.field("ST_Y({0}::geometry)", Double.class, APP_USER.HOME_LOCATION),
                        DSL.field("ST_X({0}::geometry)", Double.class, APP_USER.HOME_LOCATION))
                .from(APP_USER)
                .where(APP_USER.ID.eq(id))
                .and(APP_USER.DELETED_AT.isNull())
                .and(APP_USER.HOME_LOCATION.isNotNull())
                .fetchOptional(r -> new GeoPoint(r.value1(), r.value2()));
    }

    public void markOnboarded(UUID id, Instant now) {
        dsl.update(APP_USER)
                .set(APP_USER.ONBOARDED_AT, at(now))
                .set(APP_USER.UPDATED_AT, at(now))
                .where(APP_USER.ID.eq(id))
                .and(APP_USER.ONBOARDED_AT.isNull())
                .execute();
    }

    public void softDelete(UUID id, Instant now) {
        dsl.update(APP_USER)
                .set(APP_USER.DELETED_AT, at(now))
                .set(APP_USER.UPDATED_AT, at(now))
                .where(APP_USER.ID.eq(id))
                .and(APP_USER.DELETED_AT.isNull())
                .execute();
    }

    /**
     * Accounts deleted before the instant, oldest deletion first (the purge's work list), after
     * skipping the first {@code skip}.
     */
    public List<UUID> deletedBefore(Instant instant, int skip, int limit) {
        return dsl.select(APP_USER.ID)
                .from(APP_USER)
                .where(APP_USER.DELETED_AT.lt(at(instant)))
                .orderBy(APP_USER.DELETED_AT, APP_USER.ID)
                .offset(skip)
                .limit(limit)
                .fetch(APP_USER.ID);
    }

    /** Removes the row for good; everything referencing it with {@code on delete cascade} goes with it. */
    public void delete(UUID id) {
        dsl.deleteFrom(APP_USER).where(APP_USER.ID.eq(id)).execute();
    }

    public List<GenrePreference> genres(UUID userId) {
        return dsl.select(USER_GENRE_PREFERENCE.GENRE_ID, USER_GENRE_PREFERENCE.WEIGHT)
                .from(USER_GENRE_PREFERENCE)
                .where(USER_GENRE_PREFERENCE.USER_ID.eq(userId))
                .orderBy(USER_GENRE_PREFERENCE.WEIGHT.desc(), USER_GENRE_PREFERENCE.GENRE_ID)
                .fetch(r -> new GenrePreference(r.value1(), r.value2()));
    }

    /** Replaces the explicit preferences wholesale (onboarding and settings always send the full list). */
    public void replaceGenres(UUID userId, List<GenrePreference> preferences, Instant now) {
        dsl.deleteFrom(USER_GENRE_PREFERENCE)
                .where(USER_GENRE_PREFERENCE.USER_ID.eq(userId))
                .execute();
        var insert = dsl.insertInto(
                USER_GENRE_PREFERENCE,
                USER_GENRE_PREFERENCE.USER_ID,
                USER_GENRE_PREFERENCE.GENRE_ID,
                USER_GENRE_PREFERENCE.WEIGHT,
                USER_GENRE_PREFERENCE.CREATED_AT);
        for (GenrePreference p : preferences) {
            insert = insert.values(userId, p.genreId(), p.weight(), at(now));
        }
        if (!preferences.isEmpty()) {
            insert.execute();
        }
    }

    static UserRecord toRecord(Record r) {
        return new UserRecord(
                r.get(APP_USER.ID),
                r.get(APP_USER.EMAIL),
                r.get(APP_USER.HANDLE),
                r.get(APP_USER.DISPLAY_NAME),
                r.get(APP_USER.BIO),
                r.get(APP_USER.ROLE),
                r.get(APP_USER.PASSWORD_HASH),
                r.get(APP_USER.HOME_CITY_ID),
                instant(r.get(APP_USER.ONBOARDED_AT)),
                instant(r.get(APP_USER.CREATED_AT)),
                instant(r.get(APP_USER.DELETED_AT)),
                r.get(APP_USER.SHOW_GIG_STATS));
    }

    static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(OffsetDateTime at) {
        return at == null ? null : at.toInstant();
    }
}
