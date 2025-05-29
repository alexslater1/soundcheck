package app.soundcheck.feed.internal;

import static app.soundcheck.shared.jooq.feed.Tables.SERVED_ITEM;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * The serve-time feature log ({@code feed.served_item}): one row per item actually sent to a
 * client, with the score and the exact feature vector it was ranked on. This is the lab's training
 * data: features are logged at the moment of serving, never recomputed later from tables that have
 * moved on.
 */
@Repository
public class ServedItemLog {

    /** An item of a page, as served. */
    public record Served(int position, UUID postId, double score, Float[] features, boolean exploration) {}

    /** Stands in for a purged account (the same id the event log uses). */
    static final UUID TOMBSTONE = new UUID(0L, 0L);

    private final DSLContext dsl;

    ServedItemLog(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** One multi-row INSERT per page. */
    void record(UUID sessionId, UUID userId, UUID configId, Instant servedAt, List<Served> page) {
        if (page.isEmpty()) {
            return;
        }
        var insert = dsl.insertInto(
                SERVED_ITEM,
                SERVED_ITEM.SESSION_ID,
                SERVED_ITEM.POSITION,
                SERVED_ITEM.SERVED_AT,
                SERVED_ITEM.USER_ID,
                SERVED_ITEM.POST_ID,
                SERVED_ITEM.CONFIG_ID,
                SERVED_ITEM.SCORE,
                SERVED_ITEM.FEATURES,
                SERVED_ITEM.EXPLORATION);
        for (Served item : page) {
            insert = insert.values(
                    sessionId,
                    item.position(),
                    servedAt.atOffset(ZoneOffset.UTC),
                    userId,
                    item.postId(),
                    configId,
                    (float) item.score(),
                    item.features(),
                    item.exploration());
        }
        insert.execute();
    }

    /** Expected index: served_item_user (user_id, served_at). */
    void anonymise(UUID userId) {
        dsl.update(SERVED_ITEM)
                .set(SERVED_ITEM.USER_ID, TOMBSTONE)
                .where(SERVED_ITEM.USER_ID.eq(userId))
                .execute();
    }
}
