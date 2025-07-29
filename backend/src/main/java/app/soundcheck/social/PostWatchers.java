package app.soundcheck.social;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Who hears about a new post because they follow one of its acts or its venue at the
 * POSTS or ALL level. Social answers, because it owns the follows, the blocks and the privacy rule.
 */
public interface PostWatchers {

    /**
     * One entry per user, for the first act in {@code artistIds} they follow at POSTS or ALL, else for the
     * venue. Left out: the author; anyone with a block between them and the author; and, when the author's
     * profile is private, anyone who does not follow the author.
     */
    List<Watcher> of(UUID authorId, List<UUID> artistIds, @Nullable UUID venueId);

    enum Kind {
        ARTIST,
        VENUE
    }

    /** {@code subjectId}: the artist or the venue the user follows. */
    record Watcher(UUID userId, Kind kind, UUID subjectId) {}
}
