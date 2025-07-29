package app.soundcheck.notifications.internal;

import app.soundcheck.content.CommentAdded;
import app.soundcheck.content.PostFailed;
import app.soundcheck.content.PostLiked;
import app.soundcheck.content.PostPublished;
import app.soundcheck.content.PostTagged;
import app.soundcheck.social.FollowRequestAccepted;
import app.soundcheck.social.FollowRequestWithdrawn;
import app.soundcheck.social.FollowRequested;
import app.soundcheck.social.NewGigsFound;
import app.soundcheck.social.UserBlocked;
import app.soundcheck.social.UserFollowed;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Turns domain events into inbox rows. Each listener runs after the publishing transaction
 * has committed, in its own transaction, and is retried through the outbox if it fails; so a like is
 * never slowed down or failed by its notification. Not restricted to a role: {@code PostPublished} after
 * a transcode is published in the worker process, and a listener only runs where the event is published.
 */
@Component
class NotificationListeners {

    private final NotificationService notifications;

    NotificationListeners(NotificationService notifications) {
        this.notifications = notifications;
    }

    @ApplicationModuleListener
    void on(UserFollowed event) {
        notifications.record(event.followeeId(), "NEW_FOLLOWER", event.followerId(), null, null);
    }

    /** The private profile's owner is asked, with Accept and Decline in Activity. */
    @ApplicationModuleListener
    void on(FollowRequested event) {
        notifications.record(event.ownerId(), "FOLLOW_REQUEST", event.requesterId(), null, null);
    }

    /** The requester is told; the owner's request entry becomes "started following you". */
    @ApplicationModuleListener
    void on(FollowRequestAccepted event) {
        notifications.requestAccepted(event.ownerId(), event.requesterId());
        notifications.record(event.requesterId(), "FOLLOW_ACCEPTED", event.ownerId(), null, null);
    }

    /** Declined or cancelled: the owner's entry goes; the requester is not told. */
    @ApplicationModuleListener
    void on(FollowRequestWithdrawn event) {
        notifications.requestWithdrawn(event.ownerId(), event.requesterId());
    }

    /** Collapsed: while an unread like notification for the post exists, further likes add nothing. */
    @ApplicationModuleListener
    void on(PostLiked event) {
        notifications.record(event.postAuthorId(), "POST_LIKED", event.likerId(), event.postId(), null);
    }

    @ApplicationModuleListener
    void on(CommentAdded event) {
        notifications.record(
                event.postAuthorId(), "POST_COMMENTED", event.commenterId(), event.postId(), event.commentId());
    }

    /** Only for a post that had been waiting for its clip; one published at once needs no "ready" news. */
    @ApplicationModuleListener
    void on(PostPublished event) {
        if (event.afterProcessing()) {
            notifications.record(event.authorId(), "POST_READY", null, event.postId(), null);
        }
        notifications.recordNewPost(event.postId()); // followers of its acts and venue, if they asked
    }

    /** Each person tagged in a live post is told once, by its author. */
    @ApplicationModuleListener
    void on(PostTagged event) {
        for (UUID userId : event.userIds()) {
            notifications.record(userId, "POST_TAGGED", event.authorId(), event.postId(), null);
        }
    }

    @ApplicationModuleListener
    void on(PostFailed event) {
        notifications.record(event.authorId(), "POST_FAILED", null, event.postId(), null);
    }

    /** One inbox entry per alert, listing the new gigs that match the user's follows and searches. */
    @ApplicationModuleListener
    void on(NewGigsFound event) {
        notifications.recordNewGigs(event);
    }

    @ApplicationModuleListener
    void on(UserBlocked event) {
        notifications.forgetBetween(event.blockerId(), event.blockedId());
    }
}
