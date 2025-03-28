package app.soundcheck.content;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Content's read API for modules that only need to point at a post (notifications). */
public interface PostLookup {

    /** Briefs of the posts that still exist (deleted ones are absent). */
    Map<UUID, PostBrief> briefs(Collection<UUID> postIds);

    /** Enough of a post to show a notification about it. */
    record PostBrief(UUID id, UUID authorId, String status, int likeCount, @Nullable String thumbUrl) {}
}
