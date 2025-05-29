package app.soundcheck.content;

import app.soundcheck.shared.web.Page;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Content's lists of posts for the feed module. */
public interface PostFeed {

    /**
     * Published posts by any of the authors, newest first (keyset on {@code (published_at, id)}), without
     * the posts the viewer has hidden ("show me less") and without authors blocked either way.
     */
    Page<Post> byAuthors(UUID viewerId, Collection<UUID> authorIds, @Nullable String cursor, @Nullable Integer limit);

    /**
     * The given posts as the viewer sees them, in the order of the ids. A post that is no longer
     * published, or whose author is gone or blocked, is left out.
     */
    List<Post> byIds(UUID viewerId, List<UUID> postIds);
}
