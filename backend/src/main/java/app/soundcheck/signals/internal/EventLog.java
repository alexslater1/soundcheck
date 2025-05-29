package app.soundcheck.signals.internal;

import static app.soundcheck.shared.jooq.signals.Tables.INTERACTION_EVENT;
import static app.soundcheck.shared.jooq.signals.Tables.ROLLUP_WATERMARK;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Reading the event log in order, for the rollups. The order is the primary key
 * {@code (occurred_at, id)}; a rollup remembers the last position it has folded in (its watermark) and
 * each run reads the next bounded stretch after it.
 *
 * <p>Events become visible when their insert commits, which is not exactly in key order. A rollup
 * therefore only reads up to a moment a few seconds in the past ({@code upTo}), by which time every
 * insert stamped before it has long committed.
 */
@Repository
class EventLog {

    /** A place in the log. */
    record Position(Instant at, long id) {
        static final Position START = new Position(Instant.EPOCH, 0);
    }

    /** Events of one type on one post within one hour. */
    record PostHourCount(UUID postId, Instant hour, String eventType, long count) {}

    /** What one user did to one post, by type. */
    record UserPostCount(UUID userId, UUID postId, String eventType, long count) {}

    private final DSLContext dsl;

    EventLog(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * The rollup's watermark, locked until the transaction ends so that two runs cannot fold in the
     * same events (the row is created on first use).
     */
    Position watermarkForUpdate(String name) {
        dsl.insertInto(ROLLUP_WATERMARK)
                .set(ROLLUP_WATERMARK.NAME, name)
                .set(ROLLUP_WATERMARK.LAST_EVENT_ID, Position.START.id())
                .set(ROLLUP_WATERMARK.LAST_OCCURRED_AT, at(Position.START.at()))
                .onConflictDoNothing()
                .execute();
        return dsl.selectFrom(ROLLUP_WATERMARK)
                .where(ROLLUP_WATERMARK.NAME.eq(name))
                .forUpdate()
                .fetchSingle(r -> new Position(r.getLastOccurredAt().toInstant(), r.getLastEventId()));
    }

    void advance(String name, Position to) {
        dsl.update(ROLLUP_WATERMARK)
                .set(ROLLUP_WATERMARK.LAST_EVENT_ID, to.id())
                .set(ROLLUP_WATERMARK.LAST_OCCURRED_AT, at(to.at()))
                .where(ROLLUP_WATERMARK.NAME.eq(name))
                .execute();
    }

    /**
     * The position of the last event of the next batch: at most {@code limit} events after
     * {@code after}, none later than {@code upTo}. Empty when there is nothing to read. Expected index:
     * the primary key (occurred_at, id), with partition pruning on occurred_at.
     */
    Optional<Position> endOfBatch(Position after, Instant upTo, int limit) {
        Table<?> batch = dsl.select(INTERACTION_EVENT.OCCURRED_AT, INTERACTION_EVENT.ID)
                .from(INTERACTION_EVENT)
                .where(after(after))
                .and(INTERACTION_EVENT.OCCURRED_AT.le(at(upTo)))
                .orderBy(INTERACTION_EVENT.OCCURRED_AT, INTERACTION_EVENT.ID)
                .limit(limit)
                .asTable("batch");
        Field<OffsetDateTime> occurredAt = batch.field(INTERACTION_EVENT.OCCURRED_AT);
        Field<Long> id = batch.field(INTERACTION_EVENT.ID);
        return dsl.select(occurredAt, id)
                .from(batch)
                .orderBy(occurredAt.desc(), id.desc())
                .limit(1)
                .fetchOptional(r -> new Position(r.value1().toInstant(), r.value2()));
    }

    /** The events in {@code (after, to]}, counted per post, hour and type. */
    List<PostHourCount> countsByPostAndHour(Position after, Position to) {
        Field<OffsetDateTime> hour =
                DSL.field("date_trunc('hour', {0})", OffsetDateTime.class, INTERACTION_EVENT.OCCURRED_AT);
        return dsl.select(INTERACTION_EVENT.POST_ID, hour, INTERACTION_EVENT.EVENT_TYPE, DSL.count())
                .from(INTERACTION_EVENT)
                .where(between(after, to))
                .groupBy(INTERACTION_EVENT.POST_ID, hour, INTERACTION_EVENT.EVENT_TYPE)
                .fetch(r -> new PostHourCount(r.value1(), r.value2().toInstant(), r.value3(), r.value4()));
    }

    /**
     * The engagement in {@code (after, to]} that says something about taste, counted per user, post and
     * type. Watch progress only counts from three quarters; anonymised events are left out.
     */
    List<UserPostCount> engagementByUserAndPost(Position after, Position to) {
        Condition telling = INTERACTION_EVENT
                .EVENT_TYPE
                .in("LIKE", "UNLIKE", "COMMENT", "SHARE", "COMPLETE", "PROFILE_TAP", "GIG_TAP", "HIDE")
                .or(INTERACTION_EVENT.EVENT_TYPE.eq("WATCH_PROGRESS").and(INTERACTION_EVENT.VALUE.ge(0.75f)));
        return dsl.select(
                        INTERACTION_EVENT.USER_ID, INTERACTION_EVENT.POST_ID, INTERACTION_EVENT.EVENT_TYPE, DSL.count())
                .from(INTERACTION_EVENT)
                .where(between(after, to))
                .and(telling)
                .and(INTERACTION_EVENT.USER_ID.ne(EventRepository.TOMBSTONE))
                .groupBy(INTERACTION_EVENT.USER_ID, INTERACTION_EVENT.POST_ID, INTERACTION_EVENT.EVENT_TYPE)
                .fetch(r -> new UserPostCount(r.value1(), r.value2(), r.value3(), r.value4()));
    }

    private static Condition after(Position position) {
        return DSL.row(INTERACTION_EVENT.OCCURRED_AT, INTERACTION_EVENT.ID)
                .gt(DSL.row(at(position.at()), position.id()));
    }

    private static Condition between(Position after, Position to) {
        return after(after)
                .and(DSL.row(INTERACTION_EVENT.OCCURRED_AT, INTERACTION_EVENT.ID)
                        .le(DSL.row(at(to.at()), to.id())));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
