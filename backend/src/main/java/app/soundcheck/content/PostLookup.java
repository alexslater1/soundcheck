package app.soundcheck.content;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Content's read API for modules that only need to point at a post (notifications). */
public interface PostLookup {

    /** Briefs of the posts that still exist (deleted ones are absent). */
    Map<UUID, PostBrief> briefs(Collection<UUID> postIds);

    /** Enough of a post to show a notification about it. */
    record PostBrief(UUID id, UUID authorId, String status, int likeCount, @Nullable String thumbUrl) {}

    /**
     * What a published post is about, for the followers of its acts and its venue: its acts (the
     * post's tags, in bill order, headliner first) and the venue of its gig. Empty when the post is not
     * published.
     */
    Optional<PostSubjects> subjects(UUID postId);

    record Subject(UUID id, String name) {}

    record PostSubjects(UUID postId, UUID authorId, List<Subject> artists, @Nullable Subject venue) {}
}
