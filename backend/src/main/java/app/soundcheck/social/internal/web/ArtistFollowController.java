package app.soundcheck.social.internal.web;

import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.social.internal.ArtistFollowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code PUT/DELETE /artists/{id}/follow}: owned by social, which owns the follow table. */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1")
@Tag(name = "social", description = "Follows")
public class ArtistFollowController {

    private final ArtistFollowService follows;

    public ArtistFollowController(ArtistFollowService follows) {
        this.follows = follows;
    }

    @PutMapping("/artists/{id}/follow")
    @Operation(operationId = "followArtist", summary = "Follow an artist (idempotent)")
    public ResponseEntity<Void> follow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        follows.follow(UUID.fromString(jwt.getSubject()), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/artists/{id}/follow")
    @Operation(operationId = "unfollowArtist", summary = "Unfollow an artist (idempotent)")
    public ResponseEntity<Void> unfollow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        follows.unfollow(UUID.fromString(jwt.getSubject()), id);
        return ResponseEntity.noContent().build();
    }
}
