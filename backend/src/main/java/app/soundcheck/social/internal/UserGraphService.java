package app.soundcheck.social.internal;

import app.soundcheck.identity.UserSummary;
import app.soundcheck.identity.Users;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Cursor;
import app.soundcheck.shared.web.Page;
import app.soundcheck.social.SocialGraph;
import app.soundcheck.social.UserBlocked;
import app.soundcheck.social.UserFollowed;
import app.soundcheck.social.internal.UserGraphRepository.Edge;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User follows and blocks. Follow, unfollow, block and unblock are idempotent.
 * A block removes the follows between the two people in both directions and, from then on, each is
 * invisible to the other: the blocked person is told the blocker "does not exist" rather than that
 * they were blocked.
 */
@Service
@Transactional
public class UserGraphService implements SocialGraph {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    private final UserGraphRepository graph;
    private final Users users;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    UserGraphService(UserGraphRepository graph, Users users, ApplicationEventPublisher events, Clock clock) {
        this.graph = graph;
        this.users = users;
        this.events = events;
        this.clock = clock;
    }

    // ── follow ───────────────────────────────────────────────────────────────────────────────────

    public void follow(UUID followerId, UUID followeeId) {
        if (followerId.equals(followeeId)) {
            throw ApiException.badRequest("CANNOT_FOLLOW_SELF", "You cannot follow yourself");
        }
        // A follow and a block of the same two people must not interleave: each would pass its own check
        // and leave a follow across a block.
        graph.lockPair(followerId, followeeId);
        visibleTo(followerId, followeeId);
        if (graph.hasBlocked(followerId, followeeId)) {
            throw ApiException.conflict("USER_BLOCKED", "Unblock this user before following them");
        }
        if (graph.follow(followerId, followeeId, Instant.now(clock))) {
            events.publishEvent(new UserFollowed(followerId, followeeId));
        }
    }

    public void unfollow(UUID followerId, UUID followeeId) {
        graph.unfollow(followerId, followeeId);
    }

    // ── block ────────────────────────────────────────────────────────────────────────────────────

    /** Blocks the user and removes the follows between the two, in both directions. */
    public void block(UUID blockerId, UUID blockedId) {
        if (blockerId.equals(blockedId)) {
            throw ApiException.badRequest("CANNOT_BLOCK_SELF", "You cannot block yourself");
        }
        if (!users.exists(blockedId)) {
            throw notFound();
        }
        graph.lockPair(blockerId, blockedId);
        boolean added = graph.block(blockerId, blockedId, Instant.now(clock));
        graph.unfollow(blockerId, blockedId);
        graph.unfollow(blockedId, blockerId);
        if (added) {
            events.publishEvent(new UserBlocked(blockerId, blockedId));
        }
    }

    public void unblock(UUID blockerId, UUID blockedId) {
        graph.unblock(blockerId, blockedId);
    }

    // ── lists ────────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<UserSummary> followers(UUID viewerId, UUID userId, @Nullable String cursor, @Nullable Integer limit) {
        visibleTo(viewerId, userId);
        int size = limit(limit);
        return page(viewerId, graph.followers(userId, Cursor.decode(cursor), size), size);
    }

    @Transactional(readOnly = true)
    public Page<UserSummary> following(UUID viewerId, UUID userId, @Nullable String cursor, @Nullable Integer limit) {
        visibleTo(viewerId, userId);
        int size = limit(limit);
        return page(viewerId, graph.following(userId, Cursor.decode(cursor), size), size);
    }

    private Page<UserSummary> page(UUID viewerId, List<Edge> edges, int size) {
        boolean more = edges.size() > size;
        List<Edge> shown = more ? edges.subList(0, size) : edges;
        List<UUID> ids = shown.stream().map(Edge::userId).toList();
        Map<UUID, UserSummary> summaries = users.summaries(ids);
        Set<UUID> followed = graph.followedAmong(viewerId, ids);
        Set<UUID> hidden = graph.blockedAmong(viewerId, ids);
        List<UserSummary> items = ids.stream()
                .filter(id -> !hidden.contains(id))
                .map(summaries::get)
                .filter(Objects::nonNull) // an account that has been deleted
                .map(user -> user.withFollowing(followed.contains(user.id())))
                .toList();
        String next = more ? new Cursor(shown.getLast().since(), shown.getLast().userId()).encode() : null;
        return new Page<>(items, next);
    }

    /** The target must be a live account that has not blocked the viewer; otherwise it "does not exist". */
    private void visibleTo(UUID viewerId, UUID userId) {
        if (!users.exists(userId) || graph.hasBlocked(userId, viewerId)) {
            throw notFound();
        }
    }

    // ── SocialGraph (for other modules) ──────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> followedAmong(UUID viewerId, Collection<UUID> userIds) {
        return graph.followedAmong(viewerId, userIds);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean blockBetween(UUID a, UUID b) {
        return graph.hasBlocked(a, b) || graph.hasBlocked(b, a);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> blockedAmong(UUID viewerId, Collection<UUID> userIds) {
        return graph.blockedAmong(viewerId, userIds);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasBlocked(UUID blockerId, UUID blockedId) {
        return graph.hasBlocked(blockerId, blockedId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> followeeIds(UUID viewerId) {
        return graph.followeeIds(viewerId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> followedArtistIds(UUID userId) {
        return graph.followedArtistIds(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> blockedEitherWay(UUID userId) {
        return graph.blockedEitherWay(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public long followerCount(UUID userId) {
        return graph.followerCount(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public long followingCount(UUID userId) {
        return graph.followingCount(userId);
    }

    private static int limit(@Nullable Integer limit) {
        return limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    private static ApiException notFound() {
        return ApiException.notFound("USER_NOT_FOUND", "No such user");
    }
}
