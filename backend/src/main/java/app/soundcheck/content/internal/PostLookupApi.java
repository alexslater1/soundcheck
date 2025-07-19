package app.soundcheck.content.internal;

import app.soundcheck.content.PostLookup;
import app.soundcheck.media.MediaLibrary;
import app.soundcheck.media.Playback;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link PostLookup} for other modules. */
@Component
@Transactional(readOnly = true)
class PostLookupApi implements PostLookup {

    private final PostRepository posts;
    private final ClipRepository clips;
    private final MediaLibrary media;

    PostLookupApi(PostRepository posts, ClipRepository clips, MediaLibrary media) {
        this.posts = posts;
        this.clips = clips;
        this.media = media;
    }

    @Override
    public Map<UUID, PostBrief> briefs(Collection<UUID> postIds) {
        List<PostRow> rows = posts.findAll(postIds).stream()
                .filter(row -> !row.status().equals("DELETED"))
                .toList();
        Map<UUID, List<ClipRow>> clipsOf =
                clips.of(rows.stream().map(PostRow::id).toList());
        Map<UUID, Playback> playbacks = media.playbacks(clipsOf.values().stream()
                .flatMap(List::stream)
                .map(ClipRow::mediaId)
                .filter(Objects::nonNull)
                .toList());
        Map<UUID, PostBrief> byId = new LinkedHashMap<>();
        for (PostRow row : rows) {
            // The thumbnail of the first clip that has one (the post's cover).
            Playback playback = clipsOf.getOrDefault(row.id(), List.of()).stream()
                    .map(ClipRow::mediaId)
                    .filter(Objects::nonNull)
                    .map(playbacks::get)
                    .filter(p -> p != null && p.thumbUrl() != null)
                    .findFirst()
                    .orElse(null);
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
