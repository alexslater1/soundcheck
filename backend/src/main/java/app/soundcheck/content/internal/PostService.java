package app.soundcheck.content.internal;

import app.soundcheck.catalog.Catalog;
import app.soundcheck.catalog.CatalogViews;
import app.soundcheck.content.Post;
import app.soundcheck.content.PostDeleted;
import app.soundcheck.content.PostFailed;
import app.soundcheck.content.PostFeed;
import app.soundcheck.content.PostPublished;
import app.soundcheck.identity.Users;
import app.soundcheck.media.MediaInfo;
import app.soundcheck.media.MediaLibrary;
import app.soundcheck.shared.Ids;
import app.soundcheck.shared.idempotency.IdempotencyKeys;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Cursor;
import app.soundcheck.shared.web.Page;
import app.soundcheck.social.SocialGraph;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts: a clip becomes a post with a caption, the gig it was filmed at and the artists in it.
 * A post is created as soon as the upload is complete, usually before the clip has been transcoded: it
 * waits in PENDING_MEDIA and is published when the worker's {@code MediaReady} arrives.
 */
@Service
@Transactional
public class PostService implements PostFeed {

    public static final int MAX_ARTISTS = 10;

    /** How often a page is topped up after invisible posts were dropped from it. */
    private static final int MAX_FILL_ROUNDS = 5;

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    /** What the client sends to create a post. */
    public record CreatePost(
            UUID mediaId, @Nullable String caption, @Nullable UUID gigId, @Nullable List<UUID> artistIds) {}

    private final PostRepository posts;
    private final PostAssembler assembler;
    private final MediaLibrary media;
    private final Catalog catalog;
    private final CatalogViews catalogViews;
    private final Users users;
    private final SocialGraph social;
    private final IdempotencyKeys idempotency;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    private final Clock clock;

    PostService(
            PostRepository posts,
            PostAssembler assembler,
            MediaLibrary media,
            Catalog catalog,
            CatalogViews catalogViews,
            Users users,
            SocialGraph social,
            IdempotencyKeys idempotency,
            ApplicationEventPublisher events,
            JsonMapper json,
            Clock clock) {
        this.posts = posts;
        this.assembler = assembler;
        this.media = media;
        this.catalog = catalog;
        this.catalogViews = catalogViews;
        this.users = users;
        this.social = social;
        this.idempotency = idempotency;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    // ── create ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Creates the post for an uploaded clip. With an {@code Idempotency-Key}, repeating the request
     * returns the post created the first time. PUBLISHED at once if the clip is already READY,
     * otherwise PENDING_MEDIA.
     */
    public Post create(UUID authorId, CreatePost request, @Nullable String idempotencyKey) {
        if (idempotencyKey != null) {
            String requestJson;
            try {
                requestJson = json.writeValueAsString(request);
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
            Optional<String> remembered = idempotency.begin(authorId, idempotencyKey, "POST /posts " + requestJson);
            if (remembered.isPresent()) {
                String rememberedPostId;
                try {
                    rememberedPostId =
                            json.readTree(remembered.get()).path("postId").asText("");
                } catch (JsonProcessingException e) {
                    throw new UncheckedIOException(e);
                }
                UUID postId = UUID.fromString(rememberedPostId);
                // A retry after the post was deleted finds nothing, like any other read of it.
                return view(
                        authorId,
                        posts.find(postId)
                                .filter(p -> !p.status().equals("DELETED"))
                                .orElseThrow(PostService::notFound));
            }
        }
        // One answer for "no such clip", "not yours" and "not a video": ids cannot be probed.
        MediaInfo clip = media.find(request.mediaId())
                .filter(m -> m.ownerId().equals(authorId) && m.video())
                .orElseThrow(() -> ApiException.badRequest("MEDIA_INVALID", "mediaId must be a video you uploaded"));
        if (clip.status().equals("AWAITING_UPLOAD")) {
            throw ApiException.conflict("MEDIA_NOT_UPLOADED", "Complete the upload before posting it");
        }
        if (clip.unusable()) {
            throw ApiException.conflict("MEDIA_NOT_USABLE", "This clip could not be processed");
        }
        if (posts.findByMedia(clip.id()).isPresent()) {
            throw alreadyPosted();
        }
        if (request.gigId() != null && !catalog.gigExists(request.gigId())) {
            throw ApiException.badRequest("GIG_UNKNOWN", "Unknown gig " + request.gigId());
        }
        List<UUID> artistIds = artistsFor(authorId, request);

        Instant now = Instant.now(clock);
        boolean ready = clip.ready();
        String caption = request.caption() == null || request.caption().isBlank()
                ? null
                : request.caption().strip();
        PostRow row = new PostRow(
                Ids.newId(),
                authorId,
                clip.id(),
                request.gigId(),
                caption,
                ready ? "PUBLISHED" : "PENDING_MEDIA",
                0,
                0,
                now,
                ready ? now : null);
        try {
            posts.insert(row, artistIds);
        } catch (DataIntegrityViolationException e) {
            // Two requests for the same clip passed the check together; the unique constraint decided.
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("post_media_id_key")) {
                throw alreadyPosted();
            }
            throw e;
        }
        if (ready) {
            events.publishEvent(new PostPublished(row.id(), authorId, now, false));
        }
        if (idempotencyKey != null) {
            String responseJson;
            try {
                responseJson = json.writeValueAsString(Map.of("postId", row.id().toString()));
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
            idempotency.complete(authorId, idempotencyKey, 201, responseJson);
        }
        return view(authorId, row);
    }

    /**
     * The given artists (each must exist). When the field is left out, the gig's bill; an explicit
     * empty list means "tag nobody" (the user unticked every act).
     */
    private List<UUID> artistsFor(UUID viewerId, CreatePost request) {
        if (request.artistIds() == null) {
            if (request.gigId() == null) {
                return List.of();
            }
            List<UUID> lineup = catalogViews.lineup(request.gigId());
            return lineup.subList(0, Math.min(lineup.size(), MAX_ARTISTS));
        }
        List<UUID> requested = List.copyOf(new LinkedHashSet<>(request.artistIds()));
        if (requested.size() > MAX_ARTISTS) {
            throw ApiException.badRequest("TOO_MANY_ARTISTS", "A post can tag at most " + MAX_ARTISTS + " artists");
        }
        var known = catalogViews.artists(viewerId, requested).keySet();
        for (UUID id : requested) {
            if (!known.contains(id)) {
                throw ApiException.badRequest("ARTIST_UNKNOWN", "Unknown artist " + id);
            }
        }
        return requested;
    }

    // ── media events ─────────────────────────────────────────────────────────────────────────────

    /**
     * The clip is READY: publish the post that was waiting for it, if there is one. Safe to repeat.
     *
     * @return true when a post was published by this call
     */
    public boolean mediaReady(UUID mediaId) {
        Optional<PostRow> post = posts.findByMedia(mediaId);
        Instant now = Instant.now(clock);
        if (post.isEmpty() || !posts.publish(post.get().id(), now)) {
            return false;
        }
        events.publishEvent(new PostPublished(post.get().id(), post.get().authorId(), now, true));
        return true;
    }

    /**
     * The clip can never be played: the post waiting for it is FAILED. Safe to repeat.
     *
     * @return true when a post was failed by this call
     */
    public boolean mediaFailed(UUID mediaId) {
        Optional<PostRow> post = posts.findByMedia(mediaId);
        if (post.isEmpty() || !posts.fail(post.get().id(), Instant.now(clock))) {
            return false;
        }
        events.publishEvent(new PostFailed(post.get().id(), post.get().authorId()));
        return true;
    }

    /**
     * Catches up posts whose media event was missed (a crash between the worker's commit and the
     * listener, or a post created in the instant the clip became READY).
     *
     * @return how many posts changed
     */
    public int reconcilePending(Instant createdBefore, int limit) {
        List<PostRow> pending = posts.pendingCreatedBefore(createdBefore, limit);
        Map<UUID, MediaInfo> clips =
                media.findAll(pending.stream().map(PostRow::mediaId).toList());
        int changed = 0;
        for (PostRow post : pending) {
            MediaInfo clip = clips.get(post.mediaId());
            if (clip == null || clip.unusable()) {
                changed += mediaFailed(post.mediaId()) ? 1 : 0;
            } else if (clip.ready()) {
                changed += mediaReady(post.mediaId()) ? 1 : 0;
            }
        }
        return changed;
    }

    // ── read ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * One post. Everyone sees a PUBLISHED post; only its author sees it while it is pending or failed;
     * a deleted or hidden post looks as if it never existed (hidden ones stay visible to their author).
     */
    @Transactional(readOnly = true)
    public Post get(UUID viewerId, UUID postId) {
        PostRow row = posts.find(postId)
                .filter(p -> !p.status().equals("DELETED"))
                .filter(p -> p.published() || p.authorId().equals(viewerId))
                .orElseThrow(PostService::notFound);
        return view(viewerId, row);
    }

    @Transactional(readOnly = true)
    public Page<Post> byAuthor(UUID viewerId, UUID authorId, @Nullable String cursor, @Nullable Integer limit) {
        // A deleted account, or one that has blocked the viewer, "does not exist" for the viewer.
        if (!users.exists(authorId) || social.hasBlocked(authorId, viewerId)) {
            throw ApiException.notFound("USER_NOT_FOUND", "No such user");
        }
        return publishedPage(viewerId, PostRepository.Scope.author(authorId), cursor, limit);
    }

    @Transactional(readOnly = true)
    public Page<Post> byGig(UUID viewerId, UUID gigId, @Nullable String cursor, @Nullable Integer limit) {
        if (!catalog.gigExists(gigId)) {
            throw ApiException.notFound("GIG_NOT_FOUND", "No gig " + gigId);
        }
        return publishedPage(viewerId, PostRepository.Scope.gig(gigId), cursor, limit);
    }

    @Transactional(readOnly = true)
    public Page<Post> byArtist(UUID viewerId, UUID artistId, @Nullable String cursor, @Nullable Integer limit) {
        if (!catalog.artistExists(artistId)) {
            throw ApiException.notFound("ARTIST_NOT_FOUND", "No artist " + artistId);
        }
        return publishedPage(viewerId, PostRepository.Scope.artist(artistId), cursor, limit);
    }

    /** Clips filmed at a venue: the posts of the gigs held there. */
    @Transactional(readOnly = true)
    public Page<Post> byVenue(UUID viewerId, UUID venueId, @Nullable String cursor, @Nullable Integer limit) {
        if (!catalogViews.venueExists(venueId)) {
            throw ApiException.notFound("VENUE_NOT_FOUND", "No venue " + venueId);
        }
        return publishedPage(
                viewerId, PostRepository.Scope.gigs(catalogViews.gigIdsAtVenue(venueId, 500)), cursor, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Post> byAuthors(
            UUID viewerId, Collection<UUID> authorIds, @Nullable String cursor, @Nullable Integer limit) {
        return visiblePage(viewerId, cursor, limit, (from, size) -> posts.byAuthors(authorIds, viewerId, from, size));
    }

    /** The author's own posts, including those still processing or failed (the profile grid's badges). */
    @Transactional(readOnly = true)
    public Page<Post> mine(UUID authorId, @Nullable String cursor, @Nullable Integer limit) {
        int size = limit(limit);
        List<PostRow> rows = posts.ownPosts(authorId, Cursor.decode(cursor), size);
        boolean more = rows.size() > size;
        List<PostRow> shown = more ? rows.subList(0, size) : rows;
        String next =
                more ? new Cursor(shown.getLast().createdAt(), shown.getLast().id()).encode() : null;
        return new Page<>(assembler.assemble(authorId, shown), next);
    }

    private Page<Post> publishedPage(
            UUID viewerId, PostRepository.Scope scope, @Nullable String cursor, @Nullable Integer limit) {
        return visiblePage(viewerId, cursor, limit, (from, size) -> posts.published(scope, from, size));
    }

    /**
     * A page of published posts the viewer may see. The assembler drops posts whose author is blocked
     * or deleted, which happens after the rows were read; so that a page is not short (or empty with
     * more to come) the missing places are read again from where the last read ended, a bounded number
     * of times. Each read asks for exactly the places still free, so the cursor is always the last row
     * read.
     *
     * @param read (position, n) → up to n + 1 rows after the position, newest first
     */
    private Page<Post> visiblePage(
            UUID viewerId,
            @Nullable String cursor,
            @Nullable Integer limit,
            BiFunction<@Nullable Cursor, Integer, List<PostRow>> read) {
        int size = limit(limit);
        List<Post> items = new ArrayList<>(size);
        Cursor position = Cursor.decode(cursor);
        for (int round = 0; round < MAX_FILL_ROUNDS; round++) {
            int wanted = size - items.size();
            List<PostRow> rows = read.apply(position, wanted);
            boolean more = rows.size() > wanted;
            List<PostRow> shown = more ? rows.subList(0, wanted) : rows;
            items.addAll(assembler.assemble(viewerId, shown));
            if (!more) {
                return new Page<>(items, null);
            }
            position = new Cursor(shown.getLast().publishedAt(), shown.getLast().id());
            if (items.size() == size) {
                break;
            }
        }
        // Either the page is full, or the rounds ran out: the client continues from the cursor.
        return new Page<>(items, position == null ? null : position.encode());
    }

    // ── delete ───────────────────────────────────────────────────────────────────────────────────

    /** Soft delete by the author or an admin; anyone else is told the post does not exist. */
    public void delete(UUID actorId, boolean admin, UUID postId) {
        PostRow row = posts.find(postId)
                .filter(p -> !p.status().equals("DELETED"))
                .filter(p -> admin || p.authorId().equals(actorId))
                .orElseThrow(PostService::notFound);
        if (posts.markDeleted(row.id(), Instant.now(clock))) {
            events.publishEvent(new PostDeleted(row.id(), row.authorId()));
        }
    }

    private Post view(UUID viewerId, PostRow row) {
        return assembler.assemble(viewerId, List.of(row)).stream().findFirst().orElseThrow(PostService::notFound);
    }

    private static int limit(@Nullable Integer limit) {
        return limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    static ApiException notFound() {
        return ApiException.notFound("POST_NOT_FOUND", "No such post");
    }

    private static ApiException alreadyPosted() {
        return ApiException.conflict("MEDIA_ALREADY_POSTED", "This clip has already been posted");
    }
}
