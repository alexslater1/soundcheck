package app.soundcheck.content.internal;

import app.soundcheck.catalog.GigClips;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Answers the catalog's {@link GigClips}: the number of published posts tagged to a gig. */
@Component
@Transactional(readOnly = true)
class GigClipsProvider implements GigClips {

    private final PostRepository posts;

    GigClipsProvider(PostRepository posts) {
        this.posts = posts;
    }

    @Override
    public long countAt(UUID gigId) {
        return posts.countPublishedAt(gigId);
    }
}
