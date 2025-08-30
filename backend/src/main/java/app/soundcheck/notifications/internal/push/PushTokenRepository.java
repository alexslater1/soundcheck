package app.soundcheck.notifications.internal.push;

import static app.soundcheck.shared.jooq.notify.Tables.PUSH_TOKEN;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** {@code notify.push_token} (V132): the devices each user's notifications are pushed to. */
@Repository
class PushTokenRepository {

    private final DSLContext dsl;

    PushTokenRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Registers the token for the user, or moves it to them when the phone was someone else's. */
    void save(String token, UUID userId, String platform, Instant now) {
        OffsetDateTime at = now.atOffset(ZoneOffset.UTC);
        dsl.insertInto(PUSH_TOKEN)
                .set(PUSH_TOKEN.TOKEN, token)
                .set(PUSH_TOKEN.USER_ID, userId)
                .set(PUSH_TOKEN.PLATFORM, platform)
                .set(PUSH_TOKEN.CREATED_AT, at)
                .set(PUSH_TOKEN.LAST_SEEN_AT, at)
                .onConflict(PUSH_TOKEN.TOKEN)
                .doUpdate()
                .set(PUSH_TOKEN.USER_ID, userId)
                .set(PUSH_TOKEN.PLATFORM, platform)
                .set(PUSH_TOKEN.LAST_SEEN_AT, at)
                .execute();
    }

    /** Sign-out: only the user's own token goes, so nobody can unregister someone else's phone. */
    void delete(String token, UUID userId) {
        dsl.deleteFrom(PUSH_TOKEN)
                .where(PUSH_TOKEN.TOKEN.eq(token))
                .and(PUSH_TOKEN.USER_ID.eq(userId))
                .execute();
    }

    /** FCM said the token is gone. */
    void delete(String token) {
        dsl.deleteFrom(PUSH_TOKEN).where(PUSH_TOKEN.TOKEN.eq(token)).execute();
    }

    /** Index: push_token_user. */
    List<String> tokensOf(UUID userId) {
        return dsl.select(PUSH_TOKEN.TOKEN)
                .from(PUSH_TOKEN)
                .where(PUSH_TOKEN.USER_ID.eq(userId))
                .fetch(PUSH_TOKEN.TOKEN);
    }
}
