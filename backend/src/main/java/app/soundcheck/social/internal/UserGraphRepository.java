package app.soundcheck.social.internal;

import static app.soundcheck.shared.jooq.social.Tables.ARTIST_FOLLOW;
import static app.soundcheck.shared.jooq.social.Tables.FOLLOW_REQUEST;
import static app.soundcheck.shared.jooq.social.Tables.USER_BLOCK;
import static app.soundcheck.shared.jooq.social.Tables.USER_FOLLOW;

import app.soundcheck.shared.web.Cursor;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** jOOQ access to {@code social.user_follow}, {@code social.follow_request} and {@code social.user_block}. */
@Repository
class UserGraphRepository {

    /** One edge of a followers/following list: the other user and when the follow was made. */
    record Edge(UUID userId, Instant since) {}

    private final DSLContext dsl;

    /** The Following feed looks at this many followees at most. */
    static final int MAX_FOLLOWEES = 5_000;

    UserGraphRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Serialises changes to the relationship between two users until the transaction ends: a
     * transaction-scoped advisory lock keyed on the pair, whichever way round it is given.
     */
    void lockPair(UUID a, UUID b) {
        String low = a.compareTo(b) <= 0 ? a.toString() : b.toString();
        String high = a.compareTo(b) <= 0 ? b.toString() : a.toString();
        dsl.fetch("select pg_advisory_xact_lock(hashtextextended(?, 0))", "user-pair:" + low + ":" + high);
    }

    /** @return true when the follow did not exist before */
    boolean follow(UUID followerId, UUID followeeId, Instant now) {
        return dsl.insertInto(USER_FOLLOW)
                        .set(USER_FOLLOW.FOLLOWER_ID, followerId)
                        .set(USER_FOLLOW.FOLLOWEE_ID, followeeId)
                        .set(USER_FOLLOW.CREATED_AT, at(now))
                        .onConflictDoNothing()
                        .execute()
                == 1;
    }

    boolean unfollow(UUID followerId, UUID followeeId) {
        return dsl.deleteFrom(USER_FOLLOW)
                        .where(USER_FOLLOW.FOLLOWER_ID.eq(followerId))
                        .and(USER_FOLLOW.FOLLOWEE_ID.eq(followeeId))
                        .execute()
                == 1;
    }

    // ── follow requests ────────────────────────────────────────────────────────────────

    /** @return true when the request did not exist before */
    boolean request(UUID requesterId, UUID ownerId, Instant now) {
        return dsl.insertInto(FOLLOW_REQUEST)
                        .set(FOLLOW_REQUEST.REQUESTER_ID, requesterId)
                        .set(FOLLOW_REQUEST.OWNER_ID, ownerId)
                        .set(FOLLOW_REQUEST.CREATED_AT, at(now))
                        .onConflictDoNothing()
                        .execute()
                == 1;
    }

    /** @return true when there was a request to delete */
    boolean deleteRequest(UUID requesterId, UUID ownerId) {
        return dsl.deleteFrom(FOLLOW_REQUEST)
                        .where(FOLLOW_REQUEST.REQUESTER_ID.eq(requesterId))
                        .and(FOLLOW_REQUEST.OWNER_ID.eq(ownerId))
                        .execute()
                == 1;
    }

    /** The owners among {@code ownerIds} the requester is waiting on. Expected index: the follow_request primary key. */
    Set<UUID> requestedAmong(UUID requesterId, Collection<UUID> ownerIds) {
        if (ownerIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(dsl.select(FOLLOW_REQUEST.OWNER_ID)
                .from(FOLLOW_REQUEST)
                .where(FOLLOW_REQUEST.REQUESTER_ID.eq(requesterId))
                .and(FOLLOW_REQUEST.OWNER_ID.in(ownerIds))
                .fetch(FOLLOW_REQUEST.OWNER_ID));
    }

    /** The users among {@code userIds} waiting for the owner's answer. Expected index: follow_request_owner. */
    Set<UUID> requestersAmong(UUID ownerId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(dsl.select(FOLLOW_REQUEST.REQUESTER_ID)
                .from(FOLLOW_REQUEST)
                .where(FOLLOW_REQUEST.OWNER_ID.eq(ownerId))
                .and(FOLLOW_REQUEST.REQUESTER_ID.in(userIds))
                .fetch(FOLLOW_REQUEST.REQUESTER_ID));
    }

    /** Everyone waiting for the owner's answer, oldest first. Expected index: follow_request_owner. */
    List<UUID> requesters(UUID ownerId) {
        return dsl.select(FOLLOW_REQUEST.REQUESTER_ID)
                .from(FOLLOW_REQUEST)
                .where(FOLLOW_REQUEST.OWNER_ID.eq(ownerId))
                .orderBy(FOLLOW_REQUEST.CREATED_AT, FOLLOW_REQUEST.REQUESTER_ID)
                .fetch(FOLLOW_REQUEST.REQUESTER_ID);
    }

    // ── blocks ───────────────────────────────────────────────────────────────────────────────────

    boolean block(UUID blockerId, UUID blockedId, Instant now) {
        return dsl.insertInto(USER_BLOCK)
                        .set(USER_BLOCK.BLOCKER_ID, blockerId)
                        .set(USER_BLOCK.BLOCKED_ID, blockedId)
                        .set(USER_BLOCK.CREATED_AT, at(now))
                        .onConflictDoNothing()
                        .execute()
                == 1;
    }

    boolean unblock(UUID blockerId, UUID blockedId) {
        return dsl.deleteFrom(USER_BLOCK)
                        .where(USER_BLOCK.BLOCKER_ID.eq(blockerId))
                        .and(USER_BLOCK.BLOCKED_ID.eq(blockedId))
                        .execute()
                == 1;
    }

    /** Which of the users the viewer follows. Expected index: the user_follow primary key. */
    Set<UUID> followedAmong(UUID viewerId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(dsl.select(USER_FOLLOW.FOLLOWEE_ID)
                .from(USER_FOLLOW)
                .where(USER_FOLLOW.FOLLOWER_ID.eq(viewerId))
                .and(USER_FOLLOW.FOLLOWEE_ID.in(userIds))
                .fetch(USER_FOLLOW.FOLLOWEE_ID));
    }

    /** Which of the users follow {@code userId}. Expected index: the user_follow primary key. */
    Set<UUID> followersAmong(UUID userId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(dsl.select(USER_FOLLOW.FOLLOWER_ID)
                .from(USER_FOLLOW)
                .where(USER_FOLLOW.FOLLOWEE_ID.eq(userId))
                .and(USER_FOLLOW.FOLLOWER_ID.in(userIds))
                .fetch(USER_FOLLOW.FOLLOWER_ID));
    }

    /**
     * Whom the viewer follows, the most recent {@link #MAX_FOLLOWEES} follows at most (the Following
     * feed passes these ids to content as one list). Expected index: the user_follow primary key.
     */
    Set<UUID> followeeIds(UUID viewerId) {
        return new HashSet<>(dsl.select(USER_FOLLOW.FOLLOWEE_ID)
                .from(USER_FOLLOW)
                .where(USER_FOLLOW.FOLLOWER_ID.eq(viewerId))
                .orderBy(USER_FOLLOW.CREATED_AT.desc())
                .limit(MAX_FOLLOWEES)
                .fetch(USER_FOLLOW.FOLLOWEE_ID));
    }

    /** Expected index: the artist_follow primary key (user_id, artist_id). */
    Set<UUID> followedArtistIds(UUID userId) {
        return new HashSet<>(dsl.select(ARTIST_FOLLOW.ARTIST_ID)
                .from(ARTIST_FOLLOW)
                .where(ARTIST_FOLLOW.USER_ID.eq(userId))
                .orderBy(ARTIST_FOLLOW.CREATED_AT.desc())
                .limit(MAX_FOLLOWEES)
                .fetch(ARTIST_FOLLOW.ARTIST_ID));
    }

    /** Expected indexes: the user_block primary key (as blocker) and user_block_blocked (as blocked). */
    Set<UUID> blockedEitherWay(UUID userId) {
        Set<UUID> blocked = new HashSet<>(dsl.select(USER_BLOCK.BLOCKED_ID)
                .from(USER_BLOCK)
                .where(USER_BLOCK.BLOCKER_ID.eq(userId))
                .fetch(USER_BLOCK.BLOCKED_ID));
        blocked.addAll(dsl.select(USER_BLOCK.BLOCKER_ID)
                .from(USER_BLOCK)
                .where(USER_BLOCK.BLOCKED_ID.eq(userId))
                .fetch(USER_BLOCK.BLOCKER_ID));
        return blocked;
    }

    boolean hasBlocked(UUID blockerId, UUID blockedId) {
        return dsl.fetchExists(
                USER_BLOCK, USER_BLOCK.BLOCKER_ID.eq(blockerId).and(USER_BLOCK.BLOCKED_ID.eq(blockedId)));
    }

    /**
     * The users among {@code userIds} with a block between them and the viewer, in either direction.
     * Expected indexes: the user_block primary key (viewer as blocker) and user_block_blocked (viewer
     * as blocked).
     */
    Set<UUID> blockedAmong(UUID viewerId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> blocked = new HashSet<>(dsl.select(USER_BLOCK.BLOCKED_ID)
                .from(USER_BLOCK)
                .where(USER_BLOCK.BLOCKER_ID.eq(viewerId))
                .and(USER_BLOCK.BLOCKED_ID.in(userIds))
                .fetch(USER_BLOCK.BLOCKED_ID));
        blocked.addAll(dsl.select(USER_BLOCK.BLOCKER_ID)
                .from(USER_BLOCK)
                .where(USER_BLOCK.BLOCKED_ID.eq(viewerId))
                .and(USER_BLOCK.BLOCKER_ID.in(userIds))
                .fetch(USER_BLOCK.BLOCKER_ID));
        return blocked;
    }

    /** Expected index: user_follow_followee (followee_id, created_at desc). */
    long followerCount(UUID userId) {
        return dsl.fetchCount(USER_FOLLOW, USER_FOLLOW.FOLLOWEE_ID.eq(userId));
    }

    long followingCount(UUID userId) {
        return dsl.fetchCount(USER_FOLLOW, USER_FOLLOW.FOLLOWER_ID.eq(userId));
    }

    /**
     * Who follows the user, newest follow first, keyset on {@code (created_at, follower_id)};
     * {@code limit + 1} rows. Expected index: user_follow_followee.
     */
    List<Edge> followers(UUID userId, @Nullable Cursor cursor, int limit) {
        Condition where = USER_FOLLOW.FOLLOWEE_ID.eq(userId);
        if (cursor != null) {
            where = where.and(
                    DSL.row(USER_FOLLOW.CREATED_AT, USER_FOLLOW.FOLLOWER_ID).lt(DSL.row(at(cursor.at()), cursor.id())));
        }
        return dsl.select(USER_FOLLOW.FOLLOWER_ID, USER_FOLLOW.CREATED_AT)
                .from(USER_FOLLOW)
                .where(where)
                .orderBy(USER_FOLLOW.CREATED_AT.desc(), USER_FOLLOW.FOLLOWER_ID.desc())
                .limit(limit + 1)
                .fetch(r -> new Edge(r.value1(), r.value2().toInstant()));
    }

    /** Whom the user follows, newest first. Expected index: the primary key, then a sort (lists are short). */
    List<Edge> following(UUID userId, @Nullable Cursor cursor, int limit) {
        Condition where = USER_FOLLOW.FOLLOWER_ID.eq(userId);
        if (cursor != null) {
            where = where.and(
                    DSL.row(USER_FOLLOW.CREATED_AT, USER_FOLLOW.FOLLOWEE_ID).lt(DSL.row(at(cursor.at()), cursor.id())));
        }
        return dsl.select(USER_FOLLOW.FOLLOWEE_ID, USER_FOLLOW.CREATED_AT)
                .from(USER_FOLLOW)
                .where(where)
                .orderBy(USER_FOLLOW.CREATED_AT.desc(), USER_FOLLOW.FOLLOWEE_ID.desc())
                .limit(limit + 1)
                .fetch(r -> new Edge(r.value1(), r.value2().toInstant()));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
