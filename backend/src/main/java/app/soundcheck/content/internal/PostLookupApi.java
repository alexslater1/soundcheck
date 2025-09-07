package app.soundcheck.content.internal;

import app.soundcheck.catalog.ArtistSummary;
import app.soundcheck.catalog.CatalogViews;
import app.soundcheck.catalog.GigSummary;
import app.soundcheck.content.PostLookup;
import app.soundcheck.media.MediaLibrary;
import app.soundcheck.media.Playback;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final CatalogViews catalog;

    PostLookupApi(PostRepository posts, ClipRepository clips, MediaLibrary media, CatalogViews catalog) {
        this.posts = posts;
        this.clips = clips;
        this.media = media;
        this.catalog = catalog;
    }

    @Override
    public Optional<PostSubjects> subjects(UUID postId) {
        PostRow post = posts.find(postId).filter(PostRow::published).orElse(null);
        if (post == null) {
            return Optional.empty();
        }
        List<UUID> tagged = posts.artistIds(List.of(postId)).getOrDefault(postId, List.of());
        List<UUID> bill = post.gigId() == null ? List.of() : catalog.lineup(post.gigId());
        Map<UUID, ArtistSummary> names = catalog.artists(post.authorId(), tagged);
        List<Subject> artists = tagged.stream()
                .filter(names::containsKey)
                // Bill order, headliner first; an act not on the bill (it should not happen) goes last.
                .sorted(Comparator.comparingInt(id -> bill.contains(id) ? bill.indexOf(id) : Integer.MAX_VALUE))
                .map(id -> new Subject(id, names.get(id).name()))
                .toList();
        GigSummary gig = post.gigId() == null
                ? null
                : catalog.gigs(post.authorId(), List.of(post.gigId())).get(post.gigId());
        Subject venue =
                gig == null ? null : new Subject(gig.venue().id(), gig.venue().name());
        return Optional.of(new PostSubjects(postId, post.authorId(), artists, venue));
    }

    @Override
    public Map<UUID, PostBrief> briefs(Collection<UUID> postIds) {
        // A post hidden by a moderator is gone from inboxes too, as a deleted one is.
        List<PostRow> rows = posts.findAll(postIds).stream()
                .filter(row -> !row.status().equals("DELETED") && !row.status().equals("HIDDEN"))
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
