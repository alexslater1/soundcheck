package app.soundcheck.content.internal;

import app.soundcheck.catalog.Catalog;
import app.soundcheck.catalog.CatalogViews;
import app.soundcheck.content.Post;
import app.soundcheck.content.PostDeleted;
import app.soundcheck.content.PostFailed;
import app.soundcheck.content.PostFeed;
import app.soundcheck.identity.Users;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts: one fan's clips from one gig and day, with a caption, the acts in them and
 * the people they went with. The app creates the post when Post is pressed, announcing each clip as a
 * slot; a clip joins its slot when its upload is complete ({@link PostEditService#attach}), usually before
 * it has been transcoded. The post waits in PENDING_MEDIA and is published when its first clip's
 * {@code MediaReady} arrives; the other slots show "on its way" until theirs do.
 */
@Service
@Transactional
public class PostService implements PostFeed {

    /** How often a page is topped up after invisible posts were dropped from it. */
    private static final int MAX_FILL_ROUNDS = 5;

    /** A slot whose clip has not arrived after this long is dropped (the upload was given up). */
    static final Duration SLOT_EXPIRY = Duration.ofHours(24);

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    /** What the client sends to create a post (or to add clips to its open post from the same gig and day). */
    public record CreatePost(
            List<NewClip> clips,
            @Nullable String caption,
            @Nullable UUID gigId,
            @Nullable LocalDate day,
            @Nullable List<UUID> artistIds,
            @Nullable List<UUID> taggedUserIds) {}

    private final PostRepository posts;
    private final ClipRepository clips;
    private final TagRepository tags;
    private final PostRules rules;
    private final PostAssembler assembler;
    private final Catalog catalog;
    private final CatalogViews catalogViews;
    private final Users users;
    private final SocialGraph social;
    private final AuthorVisibility visibility;
    private final IdempotencyKeys idempotency;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    private final Clock clock;

    PostService(
            PostRepository posts,
            ClipRepository clips,
            TagRepository tags,
            PostRules rules,
            PostAssembler assembler,
            Catalog catalog,
            CatalogViews catalogViews,
            Users users,
            SocialGraph social,
            AuthorVisibility visibility,
            IdempotencyKeys idempotency,
            ApplicationEventPublisher events,
            JsonMapper json,
            Clock clock) {
        this.posts = posts;
        this.clips = clips;
        this.tags = tags;
        this.rules = rules;
        this.assembler = assembler;
        this.catalog = catalog;
        this.catalogViews = catalogViews;
        this.users = users;
        this.social = social;
        this.visibility = visibility;
        this.idempotency = idempotency;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    // ── create ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Creates the post for the announced clips, or, when the author already has an open post from the
     * same gig and day, adds the clips to it (one post per fan, gig and day), with its acts and
     * tagged people added and the caption kept unless it had none. With an {@code Idempotency-Key},
     * repeating the request returns the post created the first time. PUBLISHED at once if a clip is
     * already READY, otherwise PENDING_MEDIA.
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
        rules.checkNewClips(authorId, request.clips());
        LocalDate day = rules.dayFor(request.gigId(), request.day());
        List<NewClip> newClips = rules.withSoleAct(request.gigId(), request.artistIds(), request.clips());
        List<UUID> artistIds = rules.postArtists(authorId, request.gigId(), request.artistIds(), newClips);
        List<UUID> tagged = rules.checkTaggable(authorId, request.taggedUserIds());
        String caption = request.caption() == null || request.caption().isBlank()
                ? null
                : request.caption().strip();

        Instant now = Instant.now(clock);
        PostRow post = openPost(authorId, request.gigId(), day).orElse(null);
        List<UUID> newlyTagged = new ArrayList<>();
        if (post == null) {
            PostRow fresh =
                    new PostRow(Ids.newId(), authorId, request.gigId(), day, caption, "PENDING_MEDIA", 0, 0, now, null);
            if (posts.insertUnlessOpen(fresh, artistIds)) {
                post = fresh;
            } else {
                // Another request made the open post for this gig and day in the meantime: join it.
                post = openPost(authorId, request.gigId(), day).orElseThrow();
                posts.addArtists(post.id(), artistIds);
            }
        } else {
            posts.addArtists(post.id(), artistIds);
            if (post.caption() == null && caption != null) {
                posts.setCaption(post.id(), caption, now);
            }
        }
        rules.checkRoom(clips.of(post.id()), authorId, newClips);
        try {
            rules.addClips(post.id(), authorId, newClips, now);
        } catch (DataIntegrityViolationException e) {
            // Two requests for the same clip passed the check together; the unique constraint decided.
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("post_clip_media_id_key")) {
                throw PostRules.alreadyPosted();
            }
            throw e;
        }
        for (UUID userId : tagged) {
            if (tags.tag(post.id(), userId, now)) {
                newlyTagged.add(userId);
            }
        }
        // A live post tells new tags now; a pending one tells them all when it goes live.
        if (post.published()) {
            rules.announceTags(post.id(), authorId, newlyTagged);
        }
        rules.settle(post, false);
        if (idempotencyKey != null) {
            String responseJson;
            try {
                responseJson =
                        json.writeValueAsString(Map.of("postId", post.id().toString()));
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
            idempotency.complete(authorId, idempotencyKey, 201, responseJson);
        }
        return view(authorId, posts.find(post.id()).orElseThrow());
    }

    private Optional<PostRow> openPost(UUID authorId, @Nullable UUID gigId, @Nullable LocalDate day) {
        return gigId == null || day == null ? Optional.empty() : posts.lockOpen(authorId, gigId, day);
    }

    // ── media events ─────────────────────────────────────────────────────────────────────────────

    /**
     * The clip is READY: the post waiting for it is published, or, if it is live already, the clip just
     * shows in it (and marks its owner Went). Safe to repeat.
     *
     * @return true when a post was published by this call
     */
    public boolean mediaReady(UUID mediaId) {
        return clips.findByMedia(mediaId)
                .flatMap(clip -> posts.lock(clip.postId()))
                .map(post -> rules.settle(post, true))
                .orElse(false);
    }

    /**
     * The clip can never be played: a pending post with no other clip that could still play is FAILED.
     * Safe to repeat.
     *
     * @return true when a post was failed by this call
     */
    public boolean mediaFailed(UUID mediaId) {
        return clips.findByMedia(mediaId)
                .flatMap(clip -> posts.lock(clip.postId()))
                .filter(post -> post.status().equals("PENDING_MEDIA"))
                .map(post -> rules.settle(post, true))
                .orElse(false);
    }

    /**
     * Catches up posts whose media event was missed (a crash between the worker's commit and the
     * listener, or a clip attached in the instant it became READY), and drops slots whose clip never
     * arrived within {@link #SLOT_EXPIRY} (the upload was given up), settling their posts.
     *
     * @return how many posts and slots changed
     */
    public int reconcilePending(Instant createdBefore, int limit) {
        int changed = 0;
        for (ClipRow slot : clips.waitingSince(Instant.now(clock).minus(SLOT_EXPIRY), limit)) {
            Optional<PostRow> post = posts.lock(slot.postId());
            if (post.isEmpty() || !clips.delete(slot.id())) {
                continue;
            }
            changed++;
            if (clips.of(post.get().id()).isEmpty()) {
                // Nothing ever arrived: the post had never been live (a live post has a playable clip).
                if (posts.fail(post.get().id(), Instant.now(clock))) {
                    events.publishEvent(
                            new PostFailed(post.get().id(), post.get().authorId()));
                }
            } else {
                rules.settle(post.get(), true);
            }
        }
        for (PostRow pending : posts.pendingCreatedBefore(createdBefore, limit)) {
            changed += posts.lock(pending.id())
                            .map(post -> rules.settle(post, true))
                            .orElse(false)
                    ? 1
                    : 0;
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
        // A private profile's grid is for its followers, as its stats are.
        if (visibility.hidden(viewerId, authorId)) {
            throw ApiException.forbidden("PROFILE_PRIVATE", "This profile is private");
        }
        return publishedPage(viewerId, PostRepository.Scope.author(authorId), cursor, limit);
    }

    /**
     * A gig's clips; with an act, only the clips tagged with it (the gig page's act bar). An
     * act that is not on the bill is not an error: fans may tag any act, so its clips are listed too.
     */
    @Transactional(readOnly = true)
    public Page<Post> byGig(
            UUID viewerId, UUID gigId, @Nullable UUID artistId, @Nullable String cursor, @Nullable Integer limit) {
        if (!catalog.gigExists(gigId)) {
            throw ApiException.notFound("GIG_NOT_FOUND", "No gig " + gigId);
        }
        PostRepository.Scope scope =
                artistId == null ? PostRepository.Scope.gig(gigId) : PostRepository.Scope.gigAndArtist(gigId, artistId);
        return publishedPage(viewerId, scope, cursor, limit);
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
        // Private profiles the viewer does not follow are left out before the query: the
        // assembler would drop their posts anyway, but then each page would be refilled again and again.
        Set<UUID> hidden = visibility.hiddenAmong(viewerId, authorIds);
        List<UUID> shown = authorIds.stream().filter(id -> !hidden.contains(id)).toList();
        return visiblePage(viewerId, cursor, limit, (from, size) -> posts.byAuthors(shown, viewerId, from, size));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Post> byIds(UUID viewerId, List<UUID> postIds) {
        Map<UUID, PostRow> rows = new HashMap<>();
        posts.findAll(postIds).stream().filter(PostRow::published).forEach(row -> rows.put(row.id(), row));
        return assembler.assemble(
                viewerId,
                postIds.stream().map(rows::get).filter(Objects::nonNull).toList());
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
            rules.clipsGone(clips.of(row.id()).stream().map(ClipRow::ownerId).toList(), row.gigId());
            events.publishEvent(new PostDeleted(row.id(), row.authorId()));
        }
    }

    Post view(UUID viewerId, PostRow row) {
        return assembler.assemble(viewerId, List.of(row)).stream().findFirst().orElseThrow(PostService::notFound);
    }

    static int limit(@Nullable Integer limit) {
        return limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    static ApiException notFound() {
        return ApiException.notFound("POST_NOT_FOUND", "No such post");
    }
}
