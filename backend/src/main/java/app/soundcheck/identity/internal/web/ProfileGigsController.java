package app.soundcheck.identity.internal.web;

import static app.soundcheck.identity.internal.web.MeController.userId;

import app.soundcheck.catalog.GigSummary;
import app.soundcheck.identity.internal.ProfileStatsService;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A profile's Gigs tab: the gigs the user is going to. The ones they went to are the stats' list
 * ({@code GET /users/{id}/stats/gigs}). Visible exactly where the stats are.
 */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1/users/{id}/gigs")
@Validated
@Tag(name = "stats", description = "Gig stats on profiles")
public class ProfileGigsController {

    private final ProfileStatsService stats;

    public ProfileGigsController(ProfileStatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/going")
    @Operation(
            operationId = "listUserGoingGigs",
            summary = "The gigs the user is going to (GOING, still to come), soonest first")
    public Page<GigSummary> going(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        return stats.going(userId(jwt), id, cursor, limit);
    }
}
