package app.soundcheck.content.internal.web;

import app.soundcheck.content.internal.GigCommentService;
import app.soundcheck.content.internal.GigCommentService.GigCommentView;
import app.soundcheck.content.internal.web.EngagementController.CommentRequest;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

/** The comment thread under a gig. */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "engagement", description = "Likes, comments, hide and report")
public class GigCommentController {

    private final GigCommentService comments;

    public GigCommentController(GigCommentService comments) {
        this.comments = comments;
    }

    @GetMapping("/gigs/{id}/comments")
    @Operation(operationId = "listGigComments", summary = "A gig's comments, oldest first")
    public Page<GigCommentView> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        return comments.comments(userId(jwt), id, cursor, limit);
    }

    @PostMapping("/gigs/{id}/comments")
    @Operation(operationId = "addGigComment", summary = "Comment on a gig")
    public ResponseEntity<GigCommentView> add(
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
                .body(comments.comment(userId(jwt), id, body.body(), idempotencyKey));
    }

    @DeleteMapping("/gig-comments/{id}")
    @Operation(operationId = "deleteGigComment", summary = "Delete a gig comment (its author or an admin)")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        comments.delete(userId(jwt), "ADMIN".equals(jwt.getClaimAsString("role")), id);
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
