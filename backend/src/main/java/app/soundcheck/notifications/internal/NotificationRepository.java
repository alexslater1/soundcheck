package app.soundcheck.notifications.internal;

import static app.soundcheck.shared.jooq.notify.Tables.NOTIFICATION;

import app.soundcheck.shared.web.Cursor;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** jOOQ access to {@code notify.notification}. */
@Repository
class NotificationRepository {

    record Row(
            UUID id,
            UUID userId,
            String type,
            @Nullable UUID actorId,
            @Nullable UUID postId,
            @Nullable UUID commentId,
            Instant createdAt,
            @Nullable Instant readAt) {}

    private final DSLContext dsl;

    NotificationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Adds a notification, unless a unique index says this event is already in the inbox (V012, V013):
     * one unread POST_LIKED per (user, post) and one unread NEW_FOLLOWER per (user, follower), so repeats
     * collapse; one POST_COMMENTED per comment and one POST_READY / POST_FAILED per post, ever. That
     * makes the listeners safe to run twice for one event.
     *
     * @return true when a row was inserted
     */
    boolean insert(Row row) {
        return dsl.insertInto(NOTIFICATION)
                        .set(NOTIFICATION.ID, row.id())
                        .set(NOTIFICATION.USER_ID, row.userId())
                        .set(NOTIFICATION.TYPE, row.type())
                        .set(NOTIFICATION.ACTOR_ID, row.actorId())
                        .set(NOTIFICATION.POST_ID, row.postId())
                        .set(NOTIFICATION.COMMENT_ID, row.commentId())
                        .set(NOTIFICATION.CREATED_AT, at(row.createdAt()))
                        .onConflictDoNothing()
                        .execute()
                == 1;
    }

    /** The inbox, newest first; {@code limit + 1} rows. Expected index: notification_user. */
    List<Row> inbox(UUID userId, @Nullable Cursor cursor, int limit) {
        Condition where = NOTIFICATION.USER_ID.eq(userId);
        if (cursor != null) {
            where = where.and(
                    DSL.row(NOTIFICATION.CREATED_AT, NOTIFICATION.ID).lt(DSL.row(at(cursor.at()), cursor.id())));
        }
        return dsl.selectFrom(NOTIFICATION)
                .where(where)
                .orderBy(NOTIFICATION.CREATED_AT.desc(), NOTIFICATION.ID.desc())
                .limit(limit + 1)
                .fetch(NotificationRepository::toRow);
    }

    /** Removes what the two users' inboxes say about each other (one has blocked the other). */
    int deleteBetween(UUID a, UUID b) {
        return dsl.deleteFrom(NOTIFICATION)
                .where(NOTIFICATION.USER_ID.eq(a).and(NOTIFICATION.ACTOR_ID.eq(b)))
                .or(NOTIFICATION.USER_ID.eq(b).and(NOTIFICATION.ACTOR_ID.eq(a)))
                .execute();
    }

    Optional<Row> find(UUID id) {
        return dsl.selectFrom(NOTIFICATION).where(NOTIFICATION.ID.eq(id)).fetchOptional(NotificationRepository::toRow);
    }

    /** Expected index: notification_unread (user_id) where read_at is null. */
    long unreadCount(UUID userId) {
        return dsl.fetchCount(NOTIFICATION, NOTIFICATION.USER_ID.eq(userId).and(NOTIFICATION.READ_AT.isNull()));
    }

    /** Marks as read everything of the user's that is not newer than the given notification. */
    int markReadUpTo(UUID userId, Instant createdAt, UUID id, Instant now) {
        return dsl.update(NOTIFICATION)
                .set(NOTIFICATION.READ_AT, at(now))
                .where(NOTIFICATION.USER_ID.eq(userId))
                .and(NOTIFICATION.READ_AT.isNull())
                .and(DSL.row(NOTIFICATION.CREATED_AT, NOTIFICATION.ID).le(DSL.row(at(createdAt), id)))
                .execute();
    }

    private static Row toRow(org.jooq.Record r) {
        return new Row(
                r.get(NOTIFICATION.ID),
                r.get(NOTIFICATION.USER_ID),
                r.get(NOTIFICATION.TYPE),
                r.get(NOTIFICATION.ACTOR_ID),
                r.get(NOTIFICATION.POST_ID),
                r.get(NOTIFICATION.COMMENT_ID),
                r.get(NOTIFICATION.CREATED_AT).toInstant(),
                r.get(NOTIFICATION.READ_AT) == null
                        ? null
                        : r.get(NOTIFICATION.READ_AT).toInstant());
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
