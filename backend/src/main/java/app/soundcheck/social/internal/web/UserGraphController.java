package app.soundcheck.social.internal.web;

import app.soundcheck.identity.UserSummary;
import app.soundcheck.shared.web.Page;
import app.soundcheck.social.internal.UserGraphService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** User follows, follower lists and blocks. */
@RestController
@RequestMapping("/api/v1/users/{id}")
@Validated
@Tag(name = "social", description = "Follows and blocks")
public class UserGraphController {

    private final UserGraphService graph;

    public UserGraphController(UserGraphService graph) {
        this.graph = graph;
    }

    @PutMapping("/follow")
    @Operation(operationId = "followUser", summary = "Follow a user (idempotent)")
    public ResponseEntity<Void> follow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        graph.follow(userId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/follow")
    @Operation(operationId = "unfollowUser", summary = "Unfollow a user (idempotent)")
    public ResponseEntity<Void> unfollow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        graph.unfollow(userId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/followers")
    @Operation(operationId = "listFollowers", summary = "Who follows this user, newest first")
    public Page<UserSummary> followers(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        return graph.followers(userId(jwt), id, cursor, limit);
    }

    @GetMapping("/following")
    @Operation(operationId = "listFollowing", summary = "Whom this user follows, newest first")
    public Page<UserSummary> following(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        return graph.following(userId(jwt), id, cursor, limit);
    }

    @PutMapping("/block")
    @Operation(operationId = "blockUser", summary = "Block a user (idempotent); removes follows both ways")
    public ResponseEntity<Void> block(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        graph.block(userId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/block")
    @Operation(operationId = "unblockUser", summary = "Unblock a user (idempotent)")
    public ResponseEntity<Void> unblock(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        graph.unblock(userId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
