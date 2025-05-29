package app.soundcheck.signals.internal;

import static app.soundcheck.shared.jooq.signals.Tables.INTERACTION_EVENT;
import static app.soundcheck.shared.jooq.signals.Tables.POST_USER_EVENT;
import static app.soundcheck.shared.jooq.signals.Tables.USER_ARTIST_AFFINITY;
import static app.soundcheck.shared.jooq.signals.Tables.USER_AUTHOR_AFFINITY;
import static app.soundcheck.shared.jooq.signals.Tables.USER_GENRE_AFFINITY;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Writes to the interaction event log ({@code signals.interaction_event}). */
@Repository
public class EventRepository {

    /** One event to store; the receive time is stamped by the database. */
    public record EventRow(
            UUID userId,
            UUID postId,
            @Nullable UUID feedSessionId,
            String surface,
            @Nullable Integer position,
            String eventType,
            @Nullable Float value,
            @Nullable Instant clientTs,
            @Nullable String platform) {}

    /** Events of an account that no longer exists are kept, under this id. */
    public static final UUID TOMBSTONE = new UUID(0L, 0L);

    private final DSLContext dsl;

    EventRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * One multi-row INSERT for the whole batch: one round trip, however many events. {@code occurred_at}
     * is the database's clock ({@code now()}, the same for every row of the statement), not this
     * instance's: the rollups compare it with the same clock, so API instances whose clocks disagree
     * cannot make an event look older than it is.
     */
    int insertAll(List<EventRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        var insert = dsl.insertInto(
                INTERACTION_EVENT,
                INTERACTION_EVENT.OCCURRED_AT,
                INTERACTION_EVENT.USER_ID,
                INTERACTION_EVENT.POST_ID,
                INTERACTION_EVENT.FEED_SESSION_ID,
                INTERACTION_EVENT.SURFACE,
                INTERACTION_EVENT.POSITION,
                INTERACTION_EVENT.EVENT_TYPE,
                INTERACTION_EVENT.VALUE,
                INTERACTION_EVENT.CLIENT_TS,
                INTERACTION_EVENT.PLATFORM);
        for (EventRow row : rows) {
            insert = insert.values(
                    DSL.currentOffsetDateTime(),
                    DSL.val(row.userId(), INTERACTION_EVENT.USER_ID),
                    DSL.val(row.postId(), INTERACTION_EVENT.POST_ID),
                    DSL.val(row.feedSessionId(), INTERACTION_EVENT.FEED_SESSION_ID),
                    DSL.val(row.surface(), INTERACTION_EVENT.SURFACE),
                    DSL.val(row.position(), INTERACTION_EVENT.POSITION),
                    DSL.val(row.eventType(), INTERACTION_EVENT.EVENT_TYPE),
                    DSL.val(row.value(), INTERACTION_EVENT.VALUE),
                    DSL.val(row.clientTs() == null ? null : at(row.clientTs()), INTERACTION_EVENT.CLIENT_TS),
                    DSL.val(row.platform(), INTERACTION_EVENT.PLATFORM));
        }
        return insert.execute();
    }

    /**
     * The posts shown to the user since {@code since}, most recently shown first. Expected index:
     * interaction_event_user (user_id, occurred_at), with partition pruning on occurred_at.
     */
    Set<UUID> impressedPostIds(UUID userId, Instant since, int limit) {
        return new LinkedHashSet<>(dsl.select(INTERACTION_EVENT.POST_ID)
                .from(INTERACTION_EVENT)
                .where(INTERACTION_EVENT.USER_ID.eq(userId))
                .and(INTERACTION_EVENT.OCCURRED_AT.ge(at(since)))
                .and(INTERACTION_EVENT.EVENT_TYPE.eq("IMPRESSION"))
                .groupBy(INTERACTION_EVENT.POST_ID)
                .orderBy(DSL.max(INTERACTION_EVENT.OCCURRED_AT).desc())
                .limit(limit)
                .fetch(INTERACTION_EVENT.POST_ID));
    }

    /**
     * Detaches a purged account from what it did: its events stay (the aggregates were built from them)
     * under the tombstone id, and its affinities are deleted. Expected index: interaction_event_user.
     */
    void anonymise(UUID userId) {
        dsl.update(INTERACTION_EVENT)
                .set(INTERACTION_EVENT.USER_ID, TOMBSTONE)
                .where(INTERACTION_EVENT.USER_ID.eq(userId))
                .execute();
        // What the account was counted for stays counted; the proof rows are no longer needed.
        dsl.deleteFrom(POST_USER_EVENT)
                .where(POST_USER_EVENT.USER_ID.eq(userId))
                .execute();
        dsl.deleteFrom(USER_ARTIST_AFFINITY)
                .where(USER_ARTIST_AFFINITY.USER_ID.eq(userId))
                .execute();
        dsl.deleteFrom(USER_GENRE_AFFINITY)
                .where(USER_GENRE_AFFINITY.USER_ID.eq(userId))
                .execute();
        dsl.deleteFrom(USER_AUTHOR_AFFINITY)
                .where(USER_AUTHOR_AFFINITY.USER_ID.eq(userId).or(USER_AUTHOR_AFFINITY.AUTHOR_ID.eq(userId)))
                .execute();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
