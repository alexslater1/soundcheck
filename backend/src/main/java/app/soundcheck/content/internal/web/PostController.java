package app.soundcheck.content.internal.web;

import app.soundcheck.content.Post;
import app.soundcheck.content.internal.PostService;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public PostController(PostService posts) {
        this.posts = posts;
    }

    @PostMapping("/posts")
    @Operation(
            operationId = "createPost",
            summary = "Post an uploaded clip (PENDING_MEDIA until it is transcoded, PUBLISHED if it already is)")
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
                new PostService.CreatePost(body.mediaId(), body.caption(), body.gigId(), body.artistIds()),
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(post);
    }

    @GetMapping("/posts/{id}")
    @Operation(operationId = "getPost", summary = "One post")
    public Post get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return posts.get(userId(jwt), id);
    }

    @DeleteMapping("/posts/{id}")
    @Operation(operationId = "deletePost", summary = "Delete a post (its author, or an admin)")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        posts.delete(userId(jwt), "ADMIN".equals(jwt.getClaimAsString("role")), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/posts")
    @Operation(
            operationId = "listMyPosts",
            summary = "The caller's own posts in every status (processing and failed included), newest first")
    public Page<Post> mine(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        return posts.mine(userId(jwt), cursor, limit);
    }

    @GetMapping("/users/{id}/posts")
    @Operation(operationId = "listUserPosts", summary = "A user's published posts, newest first")
    public Page<Post> byUser(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        return posts.byAuthor(userId(jwt), id, cursor, limit);
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
            @NotNull UUID mediaId,
            @Schema(nullable = true) @Size(max = 2200) String caption,
            @Schema(nullable = true) UUID gigId,
            @Schema(nullable = true) @Size(max = 10) List<@NotNull UUID> artistIds) {}
}
