package app.soundcheck.identity.internal.auth;

import static app.soundcheck.shared.jooq.identity.Tables.REFRESH_TOKEN;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** jOOQ access to {@code identity.refresh_token}; only hashes are stored. */
@Repository
public class RefreshTokenRepository {

    /** A stored refresh token. */
    public record Row(
            UUID id,
            UUID userId,
            UUID familyId,
            byte[] tokenHash,
            Instant expiresAt,
            @Nullable Instant revokedAt,
            @Nullable UUID replacedBy,
            String client) {

        public boolean revoked() {
            return revokedAt != null;
        }
    }

    private final DSLContext dsl;

    public RefreshTokenRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(Row row, @Nullable String userAgent, Instant now) {
        dsl.insertInto(REFRESH_TOKEN)
                .set(REFRESH_TOKEN.ID, row.id())
                .set(REFRESH_TOKEN.USER_ID, row.userId())
                .set(REFRESH_TOKEN.FAMILY_ID, row.familyId())
                .set(REFRESH_TOKEN.TOKEN_HASH, row.tokenHash())
                .set(REFRESH_TOKEN.EXPIRES_AT, at(row.expiresAt()))
                .set(REFRESH_TOKEN.CLIENT, row.client())
                .set(REFRESH_TOKEN.USER_AGENT, userAgent)
                .set(REFRESH_TOKEN.CREATED_AT, at(now))
                .execute();
    }

    public Optional<Row> findByHash(byte[] hash) {
        return dsl.selectFrom(REFRESH_TOKEN)
                .where(REFRESH_TOKEN.TOKEN_HASH.eq(hash))
                .fetchOptional(RefreshTokenRepository::toRow);
    }

    /** Marks one token used/revoked and, for rotation, which token replaced it. */
    public void revoke(UUID id, @Nullable UUID replacedBy, Instant now) {
        dsl.update(REFRESH_TOKEN)
                .set(REFRESH_TOKEN.REVOKED_AT, at(now))
                .set(REFRESH_TOKEN.REPLACED_BY, replacedBy)
                .where(REFRESH_TOKEN.ID.eq(id))
                .and(REFRESH_TOKEN.REVOKED_AT.isNull())
                .execute();
    }

    /** Revokes every live token of a family (reuse detected, or logout). Expected index: refresh_token_family. */
    public int revokeFamily(UUID familyId, Instant now) {
        return dsl.update(REFRESH_TOKEN)
                .set(REFRESH_TOKEN.REVOKED_AT, at(now))
                .where(REFRESH_TOKEN.FAMILY_ID.eq(familyId))
                .and(REFRESH_TOKEN.REVOKED_AT.isNull())
                .execute();
    }

    public int revokeAllForUser(UUID userId, Instant now) {
        return dsl.update(REFRESH_TOKEN)
                .set(REFRESH_TOKEN.REVOKED_AT, at(now))
                .where(REFRESH_TOKEN.USER_ID.eq(userId))
                .and(REFRESH_TOKEN.REVOKED_AT.isNull())
                .execute();
    }

    static Row toRow(Record r) {
        return new Row(
                r.get(REFRESH_TOKEN.ID),
                r.get(REFRESH_TOKEN.USER_ID),
                r.get(REFRESH_TOKEN.FAMILY_ID),
                r.get(REFRESH_TOKEN.TOKEN_HASH),
                r.get(REFRESH_TOKEN.EXPIRES_AT).toInstant(),
                r.get(REFRESH_TOKEN.REVOKED_AT) == null
                        ? null
                        : r.get(REFRESH_TOKEN.REVOKED_AT).toInstant(),
                r.get(REFRESH_TOKEN.REPLACED_BY),
                r.get(REFRESH_TOKEN.CLIENT));
    }

    static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
