package app.soundcheck.identity.internal;

import static app.soundcheck.shared.jooq.identity.Tables.APP_USER;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** jOOQ access to {@code identity.app_user}. */
@Repository
public class UserRepository {

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
