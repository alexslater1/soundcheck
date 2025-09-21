package app.soundcheck.content.internal;

import app.soundcheck.catalog.GigClips;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers the catalog's {@link GigClips}: the number of published posts tagged to a gig, and how many of
 * them come from private profiles the viewer may not see.
 */
@Component
@Transactional(readOnly = true)
class GigClipsProvider implements GigClips {

    private final PostRepository posts;
    private final AuthorVisibility visibility;

    GigClipsProvider(PostRepository posts, AuthorVisibility visibility) {
        this.posts = posts;
        this.visibility = visibility;
    }

    @Override
    public long countAt(UUID gigId) {
        return posts.countPublishedAt(gigId);
    }

    @Override
    public long privateAt(UUID viewerId, UUID gigId) {
        Map<UUID, Long> byAuthor = posts.countPublishedAtByAuthor(gigId);
        Set<UUID> hidden = visibility.hiddenAmong(viewerId, byAuthor.keySet());
        return hidden.stream().mapToLong(byAuthor::get).sum();
    }

    @Override
    public long clipsOfActAt(UUID gigId, UUID artistId) {
        return posts.countClipsOfActAt(gigId, artistId);
    }

    @Override
    public Set<UUID> gigsWithClips() {
        return posts.gigsWithPublished();
    }
}
