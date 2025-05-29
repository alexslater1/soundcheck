package app.soundcheck.content;

import java.util.Set;
import java.util.UUID;

/** Content's API for "show me less" arriving from somewhere other than its own endpoint (the event intake). */
public interface PostHides {

    /**
     * Hides the post from the user's feeds, if the user can see it. Never throws for a post that is
     * unknown, unpublished or blocked: telemetry must not fail because of one event.
     *
     * @return true when the post is visible to the user (and is now hidden, or already was)
     */
    boolean hideIfVisible(UUID userId, UUID postId);

    /** The posts the user has hidden (the most recent {@code limit}). */
    Set<UUID> hiddenBy(UUID userId, int limit);
}
