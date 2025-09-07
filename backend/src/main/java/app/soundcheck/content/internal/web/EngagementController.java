package app.soundcheck.content.internal.web;

import app.soundcheck.content.internal.EngagementService;
import app.soundcheck.content.internal.EngagementService.CommentView;
import app.soundcheck.content.internal.EngagementService.LikeState;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Likes, comments, hide and report. */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "engagement", description = "Likes, comments, hide and report")
public class EngagementController {

    private final EngagementService engagement;

    public EngagementController(EngagementService engagement) {
        this.engagement = engagement;
    }

    @PutMapping("/posts/{id}/like")
    @Operation(operationId = "likePost", summary = "Like a post (idempotent)")
    public LikeState like(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return engagement.like(userId(jwt), id);
    }

    @DeleteMapping("/posts/{id}/like")
    @Operation(operationId = "unlikePost", summary = "Remove a like (idempotent)")
    public LikeState unlike(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return engagement.unlike(userId(jwt), id);
    }

    @GetMapping("/posts/{id}/comments")
    @Operation(operationId = "listComments", summary = "A post's comments, oldest first")
    public Page<CommentView> comments(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        return engagement.comments(userId(jwt), id, cursor, limit);
    }

    @PostMapping("/posts/{id}/comments")
    @Operation(operationId = "addComment", summary = "Comment on a post")
    public ResponseEntity<CommentView> comment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CommentRequest body) {
        if (idempotencyKey != null) {
            try {
                UUID.fromString(idempotencyKey);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key must be a UUID");
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(engagement.comment(userId(jwt), id, body.body(), idempotencyKey));
    }

    @DeleteMapping("/comments/{id}")
    @Operation(operationId = "deleteComment", summary = "Delete a comment (its author, the post's author, or an admin)")
    public ResponseEntity<Void> deleteComment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        engagement.deleteComment(userId(jwt), Actors.isAdmin(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/posts/{id}/hide")
    @Operation(operationId = "hidePost", summary = "Show me less: leave this post out of my feeds")
    public ResponseEntity<Void> hide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        engagement.hide(userId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/posts/{id}/report")
    @Operation(operationId = "reportPost", summary = "Report a post to the moderators")
    public ResponseEntity<Void> report(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReportRequest body) {
        engagement.report(userId(jwt), id, body.reason(), body.details());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/comments/{id}/report")
    @Operation(operationId = "reportComment", summary = "Report a post comment to the moderators")
    public ResponseEntity<Void> reportComment(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReportRequest body) {
        engagement.reportComment(userId(jwt), id, body.reason(), body.details());
        return ResponseEntity.noContent().build();
    }

    static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public record CommentRequest(@NotBlank @Size(max = 500) String body) {}

    public record ReportRequest(
            @NotBlank
                    @Size(min = 1, max = 20)
                    @Schema(allowableValues = {"SPAM", "ABUSE", "NUDITY", "VIOLENCE", "COPYRIGHT", "OTHER"})
                    String reason,
            @Schema(nullable = true) @Size(max = 500) String details) {}
}
