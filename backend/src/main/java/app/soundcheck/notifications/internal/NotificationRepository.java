package app.soundcheck.notifications.internal;

import static app.soundcheck.shared.jooq.notify.Tables.NOTIFICATION;

import app.soundcheck.shared.Ids;
import app.soundcheck.shared.web.Cursor;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
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
            @Nullable Instant readAt,
            /** NEW_GIGS only: the snapshot of the new gigs, as JSON. */
            @Nullable String gigs,
            /** NEW_POSTS_ARTIST / NEW_POSTS_VENUE only: the artist or venue, its name, how many posts. */
            @Nullable NewPosts newPosts) {

        Row(
                UUID id,
                UUID userId,
                String type,
                @Nullable UUID actorId,
                @Nullable UUID postId,
                @Nullable UUID commentId,
                Instant createdAt,
                @Nullable Instant readAt,
                @Nullable String gigs) {
            this(id, userId, type, actorId, postId, commentId, createdAt, readAt, gigs, null);
        }

        Row(
                UUID id,
                UUID userId,
                String type,
                @Nullable UUID actorId,
                @Nullable UUID postId,
                @Nullable UUID commentId,
                Instant createdAt,
                @Nullable Instant readAt) {
            this(id, userId, type, actorId, postId, commentId, createdAt, readAt, null, null);
        }
    }

    /** What a new-posts entry is about. */
    record NewPosts(UUID subjectId, String subjectName, int postCount) {}

    /** A new-posts entry stands for every post about its subject within this long of the first. */
    static final Duration ROLL_UP = Duration.ofHours(1);

    private final DSLContext dsl;

    NotificationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Adds a notification, unless a unique index says this event is already in the inbox (V012, V013):
     * one unread POST_LIKED per (user, post) and one unread NEW_FOLLOWER per (user, follower), so repeats
     * collapse; one POST_COMMENTED per comment and one POST_READY / POST_FAILED per post, ever; one FOLLOW_REQUEST
     * per (owner, requester) and one unread FOLLOW_ACCEPTED per (requester, owner) (V091). A NEW_GIGS
     * row's id is its alert's id (the primary key). That makes the listeners safe to run twice for one
     * event.
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
                        .set(NOTIFICATION.GIGS, row.gigs() == null ? null : JSONB.valueOf(row.gigs()))
                        .onConflictDoNothing()
                        .execute()
                == 1;
    }

    /**
     * Tells the user about a new post of an artist or venue they follow, at most one entry per
     * (user, type, subject) per {@link #ROLL_UP}: an entry from the last hour counts the post instead
     * (and points at it, the newest), otherwise a new entry starts. The unique index on the subject and
     * the clock hour (V112) stops two posts published at the same moment from both starting one. The
     * same post delivered twice (the listener's retry) is counted once.
     *
     * <p>Expected indexes: notification_new_posts_recent for the look-back; notification_new_posts_once
     * for the insert.
     */
    void newPost(UUID userId, String type, UUID subjectId, String subjectName, UUID postId, Instant now) {
        var recent = dsl.select(NOTIFICATION.ID, NOTIFICATION.POST_ID)
                .from(NOTIFICATION)
                .where(NOTIFICATION.USER_ID.eq(userId))
                .and(NOTIFICATION.TYPE.eq(type))
                .and(NOTIFICATION.SUBJECT_ID.eq(subjectId))
                .and(NOTIFICATION.CREATED_AT.gt(at(now.minus(ROLL_UP))))
                .orderBy(NOTIFICATION.CREATED_AT.desc())
                .limit(1)
                .forUpdate()
                .fetchOptional();
        if (recent.isPresent()) {
            if (!postId.equals(recent.get().value2())) {
                dsl.update(NOTIFICATION)
                        .set(NOTIFICATION.POST_COUNT, NOTIFICATION.POST_COUNT.plus(1))
                        .set(NOTIFICATION.POST_ID, postId)
                        .set(NOTIFICATION.SUBJECT_NAME, subjectName)
                        .where(NOTIFICATION.ID.eq(recent.get().value1()))
                        .execute();
            }
            return;
        }
        dsl.insertInto(NOTIFICATION)
                .set(NOTIFICATION.ID, Ids.newId())
                .set(NOTIFICATION.USER_ID, userId)
                .set(NOTIFICATION.TYPE, type)
                .set(NOTIFICATION.POST_ID, postId)
                .set(NOTIFICATION.CREATED_AT, at(now))
                .set(NOTIFICATION.SUBJECT_ID, subjectId)
                .set(NOTIFICATION.SUBJECT_NAME, subjectName)
                .set(NOTIFICATION.POST_COUNT, 1)
                .set(NOTIFICATION.ROLLUP_KEY, subjectId + ":" + now.getEpochSecond() / 3600)
                .onConflict(NOTIFICATION.USER_ID, NOTIFICATION.TYPE, NOTIFICATION.ROLLUP_KEY)
                .where(NOTIFICATION.ROLLUP_KEY.isNotNull())
                .doUpdate()
                .set(NOTIFICATION.POST_COUNT, NOTIFICATION.POST_COUNT.plus(1))
                .set(NOTIFICATION.POST_ID, DSL.excluded(NOTIFICATION.POST_ID))
                .where(NOTIFICATION.POST_ID.ne(DSL.excluded(NOTIFICATION.POST_ID)))
                .execute();
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

    /**
     * The owner's entry for a follow request they accepted becomes "started following you",
     * read: they have just answered it, so it should not count as news. Read, it cannot clash with the
     * one-unread-NEW_FOLLOWER index. Expected index: notification_follow_request.
     */
    int requestAccepted(UUID ownerId, UUID requesterId, Instant now) {
        return dsl.update(NOTIFICATION)
                .set(NOTIFICATION.TYPE, "NEW_FOLLOWER")
                .set(NOTIFICATION.READ_AT, DSL.coalesce(NOTIFICATION.READ_AT, DSL.val(at(now))))
                .where(NOTIFICATION.USER_ID.eq(ownerId))
                .and(NOTIFICATION.ACTOR_ID.eq(requesterId))
                .and(NOTIFICATION.TYPE.eq("FOLLOW_REQUEST"))
                .execute();
    }

    /** The owner's entry for a request that was declined or cancelled. Expected index: notification_follow_request. */
    int deleteRequest(UUID ownerId, UUID requesterId) {
        return dsl.deleteFrom(NOTIFICATION)
                .where(NOTIFICATION.USER_ID.eq(ownerId))
                .and(NOTIFICATION.ACTOR_ID.eq(requesterId))
                .and(NOTIFICATION.TYPE.eq("FOLLOW_REQUEST"))
                .execute();
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

    /** Marks one of the user's notifications as read, unless it already is (primary key). */
    int markRead(UUID userId, UUID id, Instant now) {
        return dsl.update(NOTIFICATION)
                .set(NOTIFICATION.READ_AT, at(now))
                .where(NOTIFICATION.ID.eq(id))
                .and(NOTIFICATION.USER_ID.eq(userId))
                .and(NOTIFICATION.READ_AT.isNull())
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
                        : r.get(NOTIFICATION.READ_AT).toInstant(),
                r.get(NOTIFICATION.GIGS) == null
                        ? null
                        : r.get(NOTIFICATION.GIGS).data(),
                r.get(NOTIFICATION.SUBJECT_ID) == null
                        ? null
                        : new NewPosts(
                                r.get(NOTIFICATION.SUBJECT_ID),
                                r.get(NOTIFICATION.SUBJECT_NAME),
                                r.get(NOTIFICATION.POST_COUNT)));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
