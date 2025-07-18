package app.soundcheck.content.internal;

import app.soundcheck.catalog.ArtistSummary;
import app.soundcheck.catalog.CatalogViews;
import app.soundcheck.catalog.GigSummary;
import app.soundcheck.content.Post;
import app.soundcheck.content.PostClip;
import app.soundcheck.identity.UserSummary;
import app.soundcheck.identity.Users;
import app.soundcheck.media.MediaLibrary;
import app.soundcheck.media.Playback;
import app.soundcheck.shared.media.PublicImages;
import app.soundcheck.social.SocialGraph;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Turns post rows into the API's {@link Post}. Everything a post shows that content does not own
 * (people, gig, artists, playback, preview images) comes from the owning module's public API, asked once
 * per page with all the ids, so a page of 20 posts costs a fixed handful of queries instead of several
 * per post.
 */
@Component
class PostAssembler {

    /** A slot that has no clip yet: nothing to play, only its preview. */
    private static final Playback WAITING = new Playback("AWAITING_UPLOAD", null, null, null, null, null, null, null);

    /** The cover of a post with no clip left (its author's view of a failed post). */
    private static final Playback NOTHING = new Playback("FAILED", null, null, null, null, null, null, null);

    private final PostRepository posts;
    private final ClipRepository clips;
    private final TagRepository tags;
    private final Users users;
    private final CatalogViews catalog;
    private final MediaLibrary media;
    private final PublicImages images;
    private final SocialGraph social;
    private final AuthorVisibility visibility;

    PostAssembler(
            PostRepository posts,
            ClipRepository clips,
            TagRepository tags,
            Users users,
            CatalogViews catalog,
            MediaLibrary media,
            PublicImages images,
            SocialGraph social,
            AuthorVisibility visibility) {
        this.posts = posts;
        this.clips = clips;
        this.tags = tags;
        this.users = users;
        this.catalog = catalog;
        this.media = media;
        this.images = images;
        this.social = social;
        this.visibility = visibility;
    }

    /**
     * Posts in the order of {@code rows}. Left out: a post whose author's account is gone, a post by
     * someone with a block between them and the viewer (blocks are enforced in reads), a post
     * by a private profile the viewer does not follow ({@link AuthorVisibility}), and, for anyone
     * but its author, a post with nothing playable left once the clips the viewer may not see are removed.
     * Each clip and each tagged person is shown under the same rules as an author (a clip is as
     * private as its owner).
     */
    List<Post> assemble(UUID viewerId, List<PostRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> postIds = rows.stream().map(PostRow::id).toList();
        Map<UUID, List<ClipRow>> clipsOf = clips.of(postIds);
        Map<UUID, List<TagRepository.Tag>> tagsOf = tags.of(postIds);

        Set<UUID> people = new LinkedHashSet<>();
        rows.forEach(row -> people.add(row.authorId()));
        clipsOf.values().forEach(list -> list.forEach(clip -> people.add(clip.ownerId())));
        tagsOf.values().forEach(list -> list.forEach(tag -> people.add(tag.userId())));
        Map<UUID, UserSummary> summaries = users.summaries(people);
        Set<UUID> followed = social.followedAmong(viewerId, summaries.keySet());
        Set<UUID> blocked = social.blockedAmong(viewerId, summaries.keySet());
        Set<UUID> hidden = visibility.hiddenAmong(viewerId, summaries.keySet());
        Map<UUID, UserSummary> visible = new HashMap<>();
        summaries.forEach((id, user) -> {
            if (id.equals(viewerId) || (!blocked.contains(id) && !hidden.contains(id))) {
                visible.put(id, user.withFollowing(followed.contains(id)));
            }
        });

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
                Stream.concat(
                                artistIds.values().stream().flatMap(List::stream),
                                clipsOf.values().stream()
                                        .flatMap(List::stream)
                                        .map(ClipRow::artistId)
                                        .filter(Objects::nonNull))
                        .distinct()
                        .toList());
        List<ClipRow> allClips = clipsOf.values().stream().flatMap(List::stream).toList();
        Map<UUID, Playback> playbacks = media.playbacks(
                allClips.stream().map(ClipRow::mediaId).filter(Objects::nonNull).toList());
        Map<UUID, String> previews = images.urls(allClips.stream()
                .filter(clip -> clip.mediaId() == null || !ready(playbacks.get(clip.mediaId())))
                .map(ClipRow::previewMediaId)
                .filter(Objects::nonNull)
                .toList());
        Set<UUID> liked = posts.likedBy(viewerId, postIds);

        List<Post> result = new ArrayList<>();
        for (PostRow row : rows) {
            UserSummary author = visible.get(row.authorId());
            if (author == null) {
                continue;
            }
            boolean byViewer = row.authorId().equals(viewerId);
            List<PostClip> shown = new ArrayList<>();
            for (ClipRow clip : clipsOf.getOrDefault(row.id(), List.of())) {
                UserSummary owner = visible.get(clip.ownerId());
                if (owner == null) {
                    continue;
                }
                Playback playback = clip.mediaId() == null ? WAITING : playbacks.get(clip.mediaId());
                if (playback == null) {
                    continue;
                }
                String status = status(clip, playback);
                if (status.equals("FAILED") && !byViewer && !clip.ownerId().equals(viewerId)) {
                    continue;
                }
                shown.add(new PostClip(
                        clip.id(),
                        clip.position(),
                        clip.artistId() == null ? null : artists.get(clip.artistId()),
                        owner,
                        status,
                        playback,
                        status.equals("READY") || clip.previewMediaId() == null
                                ? null
                                : previews.get(clip.previewMediaId())));
            }
            List<PostClip> ordered = groupedByAct(shown);
            // The cover: the first playable clip; for the author, while none is, the first clip, or (a post
            // whose every clip is gone) nothing playable, so a failed post still shows on their profile.
            Optional<Playback> cover = ordered.stream()
                    .filter(PostClip::ready)
                    .findFirst()
                    .or(() -> ordered.stream().findFirst().filter(first -> byViewer))
                    .map(PostClip::playback)
                    .or(() -> byViewer ? Optional.of(NOTHING) : Optional.empty());
            if (cover.isEmpty()) {
                continue;
            }
            List<TagRepository.Tag> postTags = tagsOf.getOrDefault(row.id(), List.of());
            result.add(new Post(
                    row.id(),
                    author,
                    row.caption(),
                    row.gigId() == null ? null : gigs.get(row.gigId()),
                    row.day(),
                    artistIds.getOrDefault(row.id(), List.of()).stream()
                            .map(artists::get)
                            .filter(Objects::nonNull)
                            .toList(),
                    cover.get(),
                    ordered,
                    ordered.size(),
                    postTags.stream()
                            .filter(TagRepository.Tag::shown)
                            .map(tag -> visible.get(tag.userId()))
                            .filter(Objects::nonNull)
                            .toList(),
                    postTags.stream()
                            .filter(tag -> tag.userId().equals(viewerId) && tag.shown())
                            .map(TagRepository.Tag::status)
                            .findFirst()
                            .orElse(null),
                    row.likeCount(),
                    row.commentCount(),
                    liked.contains(row.id()),
                    row.publishedAt(),
                    row.status()));
        }
        return result;
    }

    /**
     * The clips with each act's together, the acts in the order they first appear (the app announces them
     * in running order), and the clips of one act in their positions (the act bar).
     */
    static List<PostClip> groupedByAct(List<PostClip> clips) {
        Map<Optional<UUID>, Integer> firstAt = new HashMap<>();
        for (PostClip clip : clips) {
            firstAt.merge(act(clip), clip.position(), Math::min);
        }
        return clips.stream()
                .sorted(Comparator.comparingInt((PostClip clip) -> firstAt.get(act(clip)))
                        .thenComparingInt(PostClip::position))
                .toList();
    }

    private static Optional<UUID> act(PostClip clip) {
        return Optional.ofNullable(clip.act()).map(ArtistSummary::id);
    }

    static String status(ClipRow clip, Playback playback) {
        if (clip.mediaId() == null) {
            return "WAITING";
        }
        return switch (playback.status()) {
            case "READY" -> "READY";
            case "REJECTED", "FAILED", "DELETED" -> "FAILED";
            default -> "PROCESSING";
        };
    }

    private static boolean ready(@Nullable Playback playback) {
        return playback != null && playback.status().equals("READY");
    }
}
