package app.soundcheck.content.internal;

import app.soundcheck.content.PostLookup;
import app.soundcheck.media.MediaLibrary;
import app.soundcheck.media.Playback;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link PostLookup} for other modules. */
@Component
@Transactional(readOnly = true)
class PostLookupApi implements PostLookup {

    private final PostRepository posts;
    private final MediaLibrary media;

    PostLookupApi(PostRepository posts, MediaLibrary media) {
        this.posts = posts;
        this.media = media;
    }

    @Override
    public Map<UUID, PostBrief> briefs(Collection<UUID> postIds) {
        List<PostRow> rows = posts.findAll(postIds).stream()
                .filter(row -> !row.status().equals("DELETED"))
                .toList();
        Map<UUID, Playback> playbacks =
                media.playbacks(rows.stream().map(PostRow::mediaId).toList());
        Map<UUID, PostBrief> byId = new LinkedHashMap<>();
        for (PostRow row : rows) {
            Playback playback = playbacks.get(row.mediaId());
            byId.put(
                    row.id(),
                    new PostBrief(
                            row.id(),
                            row.authorId(),
                            row.status(),
                            row.likeCount(),
                            playback == null ? null : playback.thumbUrl()));
        }
        return byId;
    }
}
