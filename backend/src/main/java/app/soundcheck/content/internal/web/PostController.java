package app.soundcheck.content.internal.web;

import app.soundcheck.catalog.GigSummary;
import app.soundcheck.content.Post;
import app.soundcheck.content.internal.NewClip;
import app.soundcheck.content.internal.PendingGigs;
import app.soundcheck.content.internal.PostEditService;
import app.soundcheck.content.internal.PostService;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.Actors;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Posts and the lists of posts (the {@code …/posts} lists). */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "posts", description = "Posts and lists of posts")
public class PostController {

    private final PostService posts;
    private final PostEditService edits;
    private final PendingGigs pending;

    public PostController(PostService posts, PostEditService edits, PendingGigs pending) {
        this.posts = posts;
        this.edits = edits;
        this.pending = pending;
    }

    @PostMapping("/posts")
    @Operation(
            operationId = "createPost",
            summary =
                    "Post clips from a gig and day: each clip is a slot, filled now (mediaId) or when its upload is complete. Joins the caller's open post from the same gig and day if there is one. PENDING_MEDIA until a clip is playable")
    public ResponseEntity<Post> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePostRequest body) {
        if (idempotencyKey != null) {
            try {
                UUID.fromString(idempotencyKey);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key must be a UUID");
            }
        }
        Post post = posts.create(
                userId(jwt),
                new PostService.CreatePost(
                        newClips(body.clips()),
                        body.caption(),
                        body.gigId(),
                        body.day(),
                        body.artistIds(),
                        body.taggedUserIds()),
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(post);
    }

    @PatchMapping("/posts/{id}")
    @Operation(
            operationId = "editPost",
            summary =
                    "Edit your post: caption (\"\" clears it), gig and day, acts, tagged people, and the clips' order and acts (clips left out are removed). A field left out is unchanged")
    public Post edit(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody EditPostRequest body) {
        return edits.patch(
                userId(jwt),
                id,
                new PostEditService.PatchPost(
                        body.caption(),
                        body.gigId(),
                        body.day(),
                        body.artistIds(),
                        body.taggedUserIds(),
                        body.clips() == null
                                ? null
                                : body.clips().stream()
                                        .map(c -> new PostEditService.ClipPlacement(c.id(), c.artistId(), c.song()))
                                        .toList()));
    }

    @PostMapping("/posts/{id}/clips")
    @Operation(
            operationId = "addPostClips",
            summary = "Add clips to a post: its author, or someone tagged in it (which puts it on their profile)")
    public Post addClips(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody AddClipsRequest body) {
        return edits.addClips(userId(jwt), id, newClips(body.clips()));
    }

    @PatchMapping("/posts/{id}/clips/{clipId}")
    @Operation(
            operationId = "fillPostClip",
            summary = "Fill your clip's slot: its video once the upload is complete, and/or its preview frame")
    public Post fillClip(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @PathVariable UUID clipId,
            @Valid @RequestBody FillClipRequest body) {
        return edits.attach(userId(jwt), id, clipId, body.mediaId(), body.previewMediaId());
    }

    @DeleteMapping("/posts/{id}/clips/{clipId}")
    @Operation(
            operationId = "removePostClip",
            summary = "Remove a clip: its owner or the post's author; never the last one")
    public ResponseEntity<Void> removeClip(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID clipId) {
        edits.removeClip(userId(jwt), id, clipId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/posts/{id}/tags/me")
    @Operation(
            operationId = "leavePost",
            summary = "Take your tag off a post, with any clips you added; you cannot be tagged on it again")
    public ResponseEntity<Void> leave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        edits.leave(userId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/posts/{id}/tags/me")
    @Operation(
            operationId = "setPostOnProfile",
            summary = "Put a post you are tagged in on your profile, or take it off")
    public Post setOnProfile(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody MyTagRequest body) {
        return edits.setOnProfile(userId(jwt), id, body.onProfile());
    }

    @GetMapping("/posts/{id}")
    @Operation(operationId = "getPost", summary = "One post")
    public Post get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return posts.get(userId(jwt), id);
    }

    @DeleteMapping("/posts/{id}")
    @Operation(operationId = "deletePost", summary = "Delete a post (its author, or an admin)")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        posts.delete(userId(jwt), Actors.isAdmin(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/posts")
    @Operation(
            operationId = "listMyPosts",
            summary =
                    "The caller's own posts in every status (processing and failed included): by gig day, newest gig first (sort=gig), or newest posted first (sort=posted)")
    public Page<Post> mine(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false, defaultValue = "gig") @Schema(allowableValues = {"gig", "posted"})
                    String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        return posts.mine(userId(jwt), profileOrder(sort), cursor, limit);
    }

    @GetMapping("/me/gigs/unposted")
    @Operation(
            operationId = "listUnpostedGigs",
            summary =
                    "Your Pending list: gigs you went to (or said Going to, once over: wentUnconfirmed) whose day has come and that you have not posted from yet, newest first")
    public List<GigSummary> unposted(@AuthenticationPrincipal Jwt jwt) {
        return pending.of(userId(jwt));
    }

    @GetMapping("/users/{id}/posts")
    @Operation(
            operationId = "listUserPosts",
            summary =
                    "A user's published posts: by gig day, newest gig first (sort=gig), or newest posted first (sort=posted)")
    public Page<Post> byUser(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = "gig") @Schema(allowableValues = {"gig", "posted"})
                    String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        return posts.byAuthor(userId(jwt), id, profileOrder(sort), cursor, limit);
    }

    /** A profile's order; a cursor belongs to one order, so changing it starts from the first page. */
    private static PostService.ProfileOrder profileOrder(String sort) {
        return switch (sort) {
            case "gig" -> PostService.ProfileOrder.GIG;
            case "posted" -> PostService.ProfileOrder.POSTED;
            default -> throw ApiException.badRequest("SORT_INVALID", "sort must be gig or posted");
        };
    }

    @GetMapping("/gigs/{id}/posts")
    @Operation(
            operationId = "listGigPosts",
            summary = "Published clips from a gig, newest first; with artistId, only those tagged with that act")
    public Page<Post> byGig(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID artistId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        return posts.byGig(userId(jwt), id, artistId, cursor, limit);
    }

    @GetMapping("/artists/{id}/posts")
    @Operation(operationId = "listArtistPosts", summary = "Published clips tagged with an artist, newest first")
    public Page<Post> byArtist(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        return posts.byArtist(userId(jwt), id, cursor, limit);
    }

    @GetMapping("/venues/{id}/posts")
    @Operation(operationId = "listVenuePosts", summary = "Published clips from gigs at a venue, newest first")
    public Page<Post> byVenue(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        return posts.byVenue(userId(jwt), id, cursor, limit);
    }

    static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public record CreatePostRequest(
            @NotEmpty @Size(max = 60) List<@NotNull @Valid NewClipRequest> clips,
            @Schema(nullable = true) @Size(max = 2200) String caption,
            @Schema(nullable = true) UUID gigId,
            @Schema(nullable = true, description = "UK day the clips are from; default: the gig's day") LocalDate day,
            @Schema(nullable = true, description = "The post's acts; left out: the gig's bill") @Size(max = 10)
                    List<@NotNull UUID> artistIds,
            @Schema(nullable = true) @Size(max = 10) List<@NotNull UUID> taggedUserIds) {}

    /**
     * A clip announced in a post: its video if the upload is already complete, the act on stage, the song,
     * a frame.
     */
    public record NewClipRequest(
            @Schema(nullable = true) UUID mediaId,
            @Schema(nullable = true) UUID artistId,
            @Schema(nullable = true, description = "The song in the clip, as typed; blank is none") @Size(max = 120)
                    String song,
            @Schema(nullable = true) UUID previewMediaId) {}

    public record AddClipsRequest(@NotEmpty @Size(max = 60) List<@NotNull @Valid NewClipRequest> clips) {}

    public record FillClipRequest(
            @Schema(nullable = true) UUID mediaId, @Schema(nullable = true) UUID previewMediaId) {}

    public record EditPostRequest(
            @Schema(nullable = true) @Size(max = 2200) String caption,
            @Schema(nullable = true) UUID gigId,
            @Schema(nullable = true) LocalDate day,
            @Schema(nullable = true) @Size(max = 10) List<@NotNull UUID> artistIds,
            @Schema(nullable = true) @Size(max = 10) List<@NotNull UUID> taggedUserIds,
            @Schema(nullable = true) @Size(max = 60) List<@NotNull @Valid ClipPlacementRequest> clips) {}

    public record ClipPlacementRequest(
            @NotNull UUID id,
            @Schema(nullable = true) UUID artistId,
            @Schema(nullable = true, description = "The song in the clip; null or blank: none") @Size(max = 120)
                    String song) {}

    public record MyTagRequest(boolean onProfile) {}

    private static List<NewClip> newClips(List<NewClipRequest> clips) {
        return clips.stream()
                .map(c -> new NewClip(c.mediaId(), c.artistId(), c.song(), c.previewMediaId()))
                .toList();
    }
}
