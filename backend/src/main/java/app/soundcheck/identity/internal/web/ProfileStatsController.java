package app.soundcheck.identity.internal.web;

import static app.soundcheck.identity.internal.web.MeController.userId;

import app.soundcheck.catalog.AttendanceStats;
import app.soundcheck.catalog.GigStats;
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
 * A profile's stats section: acts seen and venues visited, counted from the gigs the user
 * marked WENT, and the gigs behind each line. Visible as the profile's other content is: to everyone
 * on a public profile, and on a private one ({@code PATCH /me {profileVisibility}}) to the people its owner
 * follows; always to the owner.
 */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1/users/{id}/stats")
@Validated
@Tag(name = "stats", description = "Gig stats on profiles")
public class ProfileStatsController {

    static final int DEFAULT_TOP = 10;

    private final ProfileStatsService stats;

    public ProfileStatsController(ProfileStatsService stats) {
        this.stats = stats;
    }

    @GetMapping
    @Operation(
            operationId = "getUserStats",
            summary = "Gig stats: totals, per year, most-seen acts and most-visited venues (top 10 by default)")
    public GigStats stats(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) @Min(1) @Max(AttendanceStats.MAX_TOP) Integer top) {
        return stats.stats(userId(jwt), id, top == null ? DEFAULT_TOP : top);
    }

    @GetMapping("/gigs")
    @Operation(
            operationId = "listUserStatsGigs",
            summary = "The user's WENT gigs with an act on the bill, or at a venue, newest first")
    public Page<GigSummary> gigs(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID artistId,
            @RequestParam(required = false) UUID venueId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        return stats.gigs(userId(jwt), id, artistId, venueId, cursor, limit);
    }

    /** How many gigs a line of the stats stands for, across every page. */
    public record GigCount(int gigs) {}

    @GetMapping("/gigs/count")
    @Operation(
            operationId = "countUserStatsGigs",
            summary = "How many WENT gigs the user has with an act on the bill, or at a venue (all pages)")
    public GigCount count(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID artistId,
            @RequestParam(required = false) UUID venueId) {
        return new GigCount(stats.count(userId(jwt), id, artistId, venueId));
    }
}
