package app.soundcheck.social.internal.web;

import static app.soundcheck.social.internal.web.UserGraphController.userId;

import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.social.internal.UserGraphService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's answers about who follows them: accept or decline a follow request
 * (from Activity), and remove a follower without blocking them.
 */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "social", description = "Follows and blocks")
public class FollowRequestController {

    private final UserGraphService graph;

    public FollowRequestController(UserGraphService graph) {
        this.graph = graph;
    }

    @PostMapping("/follow-requests/{userId}/accept")
    @Operation(
            operationId = "acceptFollowRequest",
            summary = "Accept a follow request: the user follows you from now on and is told; 404 if there is none")
    public ResponseEntity<Void> accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        graph.accept(userId(jwt), userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/follow-requests/{userId}")
    @Operation(operationId = "declineFollowRequest", summary = "Decline a follow request (idempotent; not told)")
    public ResponseEntity<Void> decline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        graph.decline(userId(jwt), userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/followers/{userId}")
    @Operation(
            operationId = "removeFollower",
            summary = "Remove someone from your followers without blocking them (idempotent; not told)")
    public ResponseEntity<Void> removeFollower(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        graph.removeFollower(userId(jwt), userId);
        return ResponseEntity.noContent().build();
    }
}
