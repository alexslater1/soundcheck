package app.soundcheck.identity.internal;

import static app.soundcheck.shared.jooq.identity.Tables.APP_USER;
import static app.soundcheck.shared.jooq.identity.Tables.USER_GENRE_PREFERENCE;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** jOOQ access to {@code identity.app_user} and {@code identity.user_genre_preference}. */
@Repository
public class UserRepository {

    /** One explicit genre preference. */
    public record GenrePreference(short genreId, float weight) {}

    private final DSLContext dsl;

    public UserRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(UserRecord user) {
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

    public boolean emailExists(String email) {
        return dsl.fetchExists(APP_USER, APP_USER.EMAIL.eq(email));
    }

    public boolean handleExists(String handle) {
        return dsl.fetchExists(APP_USER, APP_USER.HANDLE.eq(handle));
    }

    /** Profile fields; a null {@code homeCity} leaves the city untouched. */
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
        update.where(APP_USER.ID.eq(id)).execute();
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
                instant(r.get(APP_USER.DELETED_AT)));
    }

    static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(OffsetDateTime at) {
        return at == null ? null : at.toInstant();
    }
}
