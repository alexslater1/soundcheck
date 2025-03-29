package app.soundcheck.notifications.internal;

import app.soundcheck.content.PostLookup;
import app.soundcheck.content.PostLookup.PostBrief;
import app.soundcheck.identity.UserSummary;
import app.soundcheck.identity.Users;
import app.soundcheck.notifications.internal.NotificationRepository.Row;
import app.soundcheck.shared.Ids;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Cursor;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The notification inbox: rows are written by the listeners in {@link NotificationListeners}
 * and read here. Who did it and which post it is about are looked up when the inbox is read, through the
 * owning modules' APIs, so a renamed user or a deleted post is shown as it is now.
 */
@Service
@Transactional
public class NotificationService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    /** One entry of the inbox. {@code likeCount} is set for POST_LIKED: how many likes the post has now. */
    public record NotificationView(
            UUID id,
            @Schema(allowableValues = {"NEW_FOLLOWER", "POST_LIKED", "POST_COMMENTED", "POST_READY", "POST_FAILED"})
                    String type,
            @Schema(nullable = true) UserSummary actor,
            @Schema(nullable = true) UUID postId,
            @Schema(nullable = true) String postThumbUrl,
            @Schema(nullable = true) UUID commentId,
            @Schema(nullable = true) Integer likeCount,
            Instant createdAt,
            boolean read) {}

    /** A {@code Page<Notification>} plus {@code unreadCount}. */
    public record Inbox(List<NotificationView> items, @Schema(nullable = true) String nextCursor, long unreadCount) {}

    private final NotificationRepository notifications;
    private final Users users;
    private final PostLookup posts;
    private final Clock clock;

    NotificationService(NotificationRepository notifications, Users users, PostLookup posts, Clock clock) {
        this.notifications = notifications;
        this.users = users;
        this.posts = posts;
        this.clock = clock;
    }

    /**
     * Records that something happened to {@code userId}. Nothing is recorded when the actor is the user
     * themselves (liking or commenting on one's own post is not news).
     */
    void record(UUID userId, String type, @Nullable UUID actorId, @Nullable UUID postId, @Nullable UUID commentId) {
        if (userId.equals(actorId)) {
            return;
        }
        notifications.insert(new Row(Ids.newId(), userId, type, actorId, postId, commentId, Instant.now(clock), null));
    }

    /** A block makes two people invisible to each other, in the inbox too. */
    void forgetBetween(UUID a, UUID b) {
        notifications.deleteBetween(a, b);
    }

    @Transactional(readOnly = true)
    public Inbox inbox(UUID userId, @Nullable String cursor, @Nullable Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        List<Row> rows = notifications.inbox(userId, Cursor.decode(cursor), size);
        boolean more = rows.size() > size;
        List<Row> shown = more ? rows.subList(0, size) : rows;
        Map<UUID, UserSummary> actors = users.summaries(shown.stream()
                .map(Row::actorId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        Map<UUID, PostBrief> briefs = posts.briefs(shown.stream()
                .map(Row::postId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        List<NotificationView> items = shown.stream()
                .map(row -> {
                    PostBrief post = row.postId() == null ? null : briefs.get(row.postId());
                    return new NotificationView(
                            row.id(),
                            row.type(),
                            row.actorId() == null ? null : actors.get(row.actorId()),
                            row.postId(),
                            post == null ? null : post.thumbUrl(),
                            row.commentId(),
                            row.type().equals("POST_LIKED") && post != null ? post.likeCount() : null,
                            row.createdAt(),
                            row.readAt() != null);
                })
                .toList();
        String next =
                more ? new Cursor(shown.getLast().createdAt(), shown.getLast().id()).encode() : null;
        return new Inbox(items, next, notifications.unreadCount(userId));
    }

    /** Marks everything up to and including the given notification as read; returns the new unread count. */
    public long readUpTo(UUID userId, UUID notificationId) {
        Row upTo = notifications
                .find(notificationId)
                .filter(row -> row.userId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("NOTIFICATION_NOT_FOUND", "No such notification"));
        notifications.markReadUpTo(userId, upTo.createdAt(), upTo.id(), Instant.now(clock));
        return notifications.unreadCount(userId);
    }
}
