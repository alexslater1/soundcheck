package app.soundcheck.notifications.internal;

import app.soundcheck.content.PostLookup;
import app.soundcheck.content.PostLookup.PostBrief;
import app.soundcheck.identity.UserSummary;
import app.soundcheck.identity.Users;
import app.soundcheck.notifications.internal.NotificationRepository.Row;
import app.soundcheck.shared.Ids;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Cursor;
import app.soundcheck.social.NewGigsFound;
import app.soundcheck.social.SocialGraph;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /**
     * One entry of the inbox. {@code likeCount} is set for POST_LIKED: how many likes the post has now;
     * {@code requestPending} for FOLLOW_REQUEST.
     */
    public record NotificationView(
            UUID id,
            @Schema(
                            allowableValues = {
                                "NEW_FOLLOWER",
                                "POST_LIKED",
                                "POST_COMMENTED",
                                "POST_READY",
                                "POST_FAILED",
                                "POST_TAGGED",
                                "NEW_GIGS",
                                "FOLLOW_REQUEST",
                                "FOLLOW_ACCEPTED"
                            })
                    String type,
            @Schema(nullable = true) UserSummary actor,
            @Schema(nullable = true) UUID postId,
            @Schema(nullable = true) String postThumbUrl,
            @Schema(nullable = true) UUID commentId,
            @Schema(nullable = true) Integer likeCount,
            /** NEW_GIGS: the new gigs, soonest first (at most {@value #MAX_GIGS_SHOWN}), as they were then. */
            @Schema(nullable = true) List<AlertedGigView> gigs,
            /** NEW_GIGS: how many new gigs there were in all. */
            @Schema(nullable = true) Integer gigCount,
            /**
             * FOLLOW_REQUEST: the request is still waiting for an answer, so Activity shows Accept and Decline
             *. False for a moment after an answer, until its event has updated the entry.
             */
            @Schema(nullable = true) Boolean requestPending,
            Instant createdAt,
            boolean read) {}

    /** A gig in a NEW_GIGS alert: what it was called and where, and why the user hears about it. */
    public record AlertedGigView(
            UUID gigId,
            String title,
            Instant startsAt,
            String venueName,
            @Schema(nullable = true) String venueCity,
            @Schema(allowableValues = {"ARTIST", "VENUE", "SEARCH"}) String reason,
            /** The act or venue followed, or the saved search's name. */
            String because) {}

    /** How a NEW_GIGS snapshot is stored ({@code notify.notification.gigs}). */
    record GigSnapshot(int total, List<AlertedGigView> items) {}

    /** An alert shows this many gigs; the rest are counted. */
    static final int MAX_GIGS_SHOWN = 50;

    /** A {@code Page<Notification>} plus {@code unreadCount}. */
    public record Inbox(List<NotificationView> items, @Schema(nullable = true) String nextCursor, long unreadCount) {}

    private final NotificationRepository notifications;
    private final Users users;
    private final PostLookup posts;
    private final SocialGraph social;
    private final JsonMapper json;
    private final Clock clock;

    NotificationService(
            NotificationRepository notifications,
            Users users,
            PostLookup posts,
            SocialGraph social,
            JsonMapper json,
            Clock clock) {
        this.notifications = notifications;
        this.users = users;
        this.posts = posts;
        this.social = social;
        this.json = json;
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

    /**
     * One NEW_GIGS entry for an alert. Its id is the alert's, so a second delivery of the same
     * event adds nothing. The gigs are kept as they were: notifications cannot read the catalog.
     */
    void recordNewGigs(NewGigsFound alert) {
        if (alert.gigs().isEmpty()) {
            return;
        }
        List<AlertedGigView> items = alert.gigs().stream()
                .limit(MAX_GIGS_SHOWN)
                .map(g -> new AlertedGigView(
                        g.gigId(),
                        g.title(),
                        g.startsAt(),
                        g.venueName(),
                        g.venueCity(),
                        g.reason().name(),
                        g.because()))
                .toList();
        String snapshot;
        try {
            snapshot = json.writeValueAsString(new GigSnapshot(alert.gigs().size(), items));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        notifications.insert(
                new Row(alert.alertId(), alert.userId(), "NEW_GIGS", null, null, null, alert.at(), null, snapshot));
    }

    /** The owner accepted, so their request entry becomes "started following you" (read). */
    void requestAccepted(UUID ownerId, UUID requesterId) {
        notifications.requestAccepted(ownerId, requesterId, Instant.now(clock));
    }

    /** A request declined or cancelled leaves nothing to answer in the owner's inbox. */
    void requestWithdrawn(UUID ownerId, UUID requesterId) {
        notifications.deleteRequest(ownerId, requesterId);
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
        List<NotificationView> items = views(shown);
        String next =
                more ? new Cursor(shown.getLast().createdAt(), shown.getLast().id()).encode() : null;
        return new Inbox(items, next, notifications.unreadCount(userId));
    }

    /** One of the user's notifications (an alert's page opens it); 404 when it is not theirs. */
    @Transactional(readOnly = true)
    public NotificationView one(UUID userId, UUID notificationId) {
        Row row = notifications
                .find(notificationId)
                .filter(r -> r.userId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("NOTIFICATION_NOT_FOUND", "No such notification"));
        return views(List.of(row)).getFirst();
    }

    private List<NotificationView> views(List<Row> shown) {
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
        // Every row of one inbox belongs to one user, so one query answers which requests still wait.
        List<Row> requests =
                shown.stream().filter(r -> r.type().equals("FOLLOW_REQUEST")).toList();
        Set<UUID> waiting = requests.isEmpty()
                ? Set.of()
                : social.requestersAmong(
                        requests.getFirst().userId(),
                        requests.stream()
                                .map(Row::actorId)
                                .filter(Objects::nonNull)
                                .toList());
        return shown.stream()
                .map(row -> {
                    PostBrief post = row.postId() == null ? null : briefs.get(row.postId());
                    GigSnapshot gigs;
                    try {
                        gigs = row.gigs() == null ? null : json.readValue(row.gigs(), GigSnapshot.class);
                    } catch (JsonProcessingException e) {
                        throw new UncheckedIOException(e);
                    }
                    return new NotificationView(
                            row.id(),
                            row.type(),
                            row.actorId() == null ? null : actors.get(row.actorId()),
                            row.postId(),
                            post == null ? null : post.thumbUrl(),
                            row.commentId(),
                            row.type().equals("POST_LIKED") && post != null ? post.likeCount() : null,
                            gigs == null ? null : gigs.items(),
                            gigs == null ? null : gigs.total(),
                            row.type().equals("FOLLOW_REQUEST") ? waiting.contains(row.actorId()) : null,
                            row.createdAt(),
                            row.readAt() != null);
                })
                .toList();
    }

    /**
     * Marks one of the user's notifications as read, because they opened it; returns the new unread
     * count. One already read keeps the time it was first read.
     */
    public long read(UUID userId, UUID notificationId) {
        notifications
                .find(notificationId)
                .filter(row -> row.userId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("NOTIFICATION_NOT_FOUND", "No such notification"));
        notifications.markRead(userId, notificationId, Instant.now(clock));
        return notifications.unreadCount(userId);
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
