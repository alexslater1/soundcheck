package app.soundcheck.content.internal;

import app.soundcheck.catalog.ArtistSummary;
import app.soundcheck.catalog.CatalogViews;
import app.soundcheck.catalog.GigSummary;
import app.soundcheck.content.Post;
import app.soundcheck.identity.UserSummary;
import app.soundcheck.identity.Users;
import app.soundcheck.media.MediaLibrary;
import app.soundcheck.media.Playback;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns post rows into the API's {@link Post}. Everything a post shows that content does not own
 * (author, gig, artists, playback) comes from the owning module's public API, asked once per page with
 * all the ids, so a page of 20 posts costs a fixed handful of queries instead of several per post.
 */
@Component
class PostAssembler {

    private final PostRepository posts;
    private final Users users;
    private final CatalogViews catalog;
    private final MediaLibrary media;

    PostAssembler(PostRepository posts, Users users, CatalogViews catalog, MediaLibrary media) {
        this.posts = posts;
        this.users = users;
        this.catalog = catalog;
        this.media = media;
    }

    /** Posts in the order of {@code rows}; a post whose author's account is gone is left out. */
    List<Post> assemble(UUID viewerId, List<PostRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> postIds = rows.stream().map(PostRow::id).toList();
        Map<UUID, UserSummary> authors = users.summaries(
                rows.stream().map(PostRow::authorId).collect(LinkedHashSet::new, Set::add, Set::addAll));
        Map<UUID, GigSummary> gigs = catalog.gigs(
                viewerId,
                rows.stream()
                        .map(PostRow::gigId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());
        Map<UUID, List<UUID>> artistIds = posts.artistIds(postIds);
        Map<UUID, ArtistSummary> artists = catalog.artists(
                viewerId,
                artistIds.values().stream().flatMap(List::stream).distinct().toList());
        Map<UUID, Playback> playbacks =
                media.playbacks(rows.stream().map(PostRow::mediaId).toList());
        Set<UUID> liked = posts.likedBy(viewerId, postIds);

        List<Post> result = new ArrayList<>();
        for (PostRow row : rows) {
            UserSummary author = authors.get(row.authorId());
            Playback playback = playbacks.get(row.mediaId());
            if (author == null || playback == null) {
                continue;
            }
            result.add(new Post(
                    row.id(),
                    author,
                    row.caption(),
                    row.gigId() == null ? null : gigs.get(row.gigId()),
                    artistIds.getOrDefault(row.id(), List.of()).stream()
                            .map(artists::get)
                            .filter(Objects::nonNull)
                            .toList(),
                    playback,
                    row.likeCount(),
                    row.commentCount(),
                    liked.contains(row.id()),
                    row.publishedAt(),
                    row.status()));
        }
        return result;
    }
}
