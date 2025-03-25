package app.soundcheck.shared.idempotency;

import static app.soundcheck.shared.jooq.shared.Tables.IDEMPOTENCY_KEY;

import app.soundcheck.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code Idempotency-Key} support on {@code shared.idempotency_key}: a client that retries a
 * POST with the same key gets the first result again instead of a second resource; the same key with a
 * different request is a {@code 409 IDEMPOTENCY_KEY_REUSED}.
 *
 * <p>Usage, inside the caller's transaction: {@link #begin} first. Empty means "first time: do the work,
 * then {@link #complete} with what to remember". A value is what was remembered: replay it. The key row
 * is inserted in the same transaction as the work, so a concurrent duplicate blocks on the primary key
 * until the first request commits (then replays) or rolls back (then proceeds itself).
 */
@Component
public class IdempotencyKeys {

    /** Keys are honoured for this long; the purge job deletes older rows. */
    public static final Duration RETENTION = Duration.ofHours(24);

    private final DSLContext dsl;
    private final Clock clock;

    public IdempotencyKeys(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    /**
     * @param request what identifies the request: method, path and body, in any stable text form
     * @return the remembered result of an earlier request with this key, or empty for a new key
     * @throws ApiException 409 {@code IDEMPOTENCY_KEY_REUSED} when the key was used for a different request
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> begin(UUID userId, String key, String request) {
        byte[] hash = sha256(request);
        Instant now = Instant.now(clock);
        // An expired row with this key is treated as absent.
        dsl.deleteFrom(IDEMPOTENCY_KEY)
                .where(IDEMPOTENCY_KEY.USER_ID.eq(userId))
                .and(IDEMPOTENCY_KEY.KEY.eq(key))
                .and(IDEMPOTENCY_KEY.CREATED_AT.lt(now.minus(RETENTION).atOffset(ZoneOffset.UTC)))
                .execute();
        int inserted = dsl.insertInto(IDEMPOTENCY_KEY)
                .set(IDEMPOTENCY_KEY.USER_ID, userId)
                .set(IDEMPOTENCY_KEY.KEY, key)
                .set(IDEMPOTENCY_KEY.REQUEST_HASH, hash)
                .set(IDEMPOTENCY_KEY.CREATED_AT, now.atOffset(ZoneOffset.UTC))
                .onConflictDoNothing()
                .execute();
        if (inserted == 1) {
            return Optional.empty();
        }
        var existing = dsl.selectFrom(IDEMPOTENCY_KEY)
                .where(IDEMPOTENCY_KEY.USER_ID.eq(userId))
                .and(IDEMPOTENCY_KEY.KEY.eq(key))
                .fetchOne();
        if (existing == null || !Arrays.equals(existing.getRequestHash(), hash)) {
            throw ApiException.conflict(
                    "IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used for a different request");
        }
        if (existing.getResponseBody() == null) {
            // The first request committed without recording a result: it failed part-way. Let the client retry with a
            // new key.
            throw ApiException.conflict(
                    "IDEMPOTENCY_KEY_REUSED", "The earlier request with this Idempotency-Key did not finish");
        }
        return Optional.of(existing.getResponseBody().data());
    }

    /** Remembers the result (a JSON document) of the request that {@link #begin} let through. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(UUID userId, String key, int status, String responseJson) {
        dsl.update(IDEMPOTENCY_KEY)
                .set(IDEMPOTENCY_KEY.RESPONSE_STATUS, status)
                .set(IDEMPOTENCY_KEY.RESPONSE_BODY, JSONB.valueOf(responseJson))
                .where(IDEMPOTENCY_KEY.USER_ID.eq(userId))
                .and(IDEMPOTENCY_KEY.KEY.eq(key))
                .execute();
    }

    /** Deletes keys older than the retention; expected index: idempotency_key_created_at. */
    @Transactional
    public int purge() {
        return dsl.deleteFrom(IDEMPOTENCY_KEY)
                .where(IDEMPOTENCY_KEY.CREATED_AT.lt(
                        Instant.now(clock).minus(RETENTION).atOffset(ZoneOffset.UTC)))
                .execute();
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
