package app.soundcheck.social.internal.web;

import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.social.internal.VenueFollowService;
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

/** {@code PUT/DELETE /venues/{id}/follow}: owned by social, like the artist follow. */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1")
@Tag(name = "social", description = "Follows")
public class VenueFollowController {

    private final VenueFollowService follows;

    public VenueFollowController(VenueFollowService follows) {
        this.follows = follows;
    }

    @PutMapping("/venues/{id}/follow")
    @Operation(operationId = "followVenue", summary = "Follow a venue: its new gigs alert you (idempotent)")
    public ResponseEntity<Void> follow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        follows.follow(UUID.fromString(jwt.getSubject()), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/venues/{id}/follow")
    @Operation(operationId = "unfollowVenue", summary = "Unfollow a venue (idempotent)")
    public ResponseEntity<Void> unfollow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        follows.unfollow(UUID.fromString(jwt.getSubject()), id);
        return ResponseEntity.noContent().build();
    }
}
