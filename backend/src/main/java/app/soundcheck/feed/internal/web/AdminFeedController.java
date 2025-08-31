package app.soundcheck.feed.internal.web;

import app.soundcheck.feed.internal.FeedInspector;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.Actors;
import app.soundcheck.shared.web.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The feed inspector's API: one user's taste as ranking sees it, and the feed sessions they were
 * served. What they would see now comes from {@code POST /api/v1/admin/ranking/preview}. Operators only.
 */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1/admin/feed")
@PreAuthorize(Actors.ADMIN_ONLY)
@Validated
@Tag(name = "admin-feed", description = "Console: one user's feed, as ranked and as served (operators only)")
public class AdminFeedController {

    private final FeedInspector inspector;

    public AdminFeedController(FeedInspector inspector) {
        this.inspector = inspector;
    }

    @GetMapping("/users/{id}/profile")
    @Operation(
            operationId = "adminFeedProfile",
            summary = "A user's genre vector and their strongest artist and author affinities")
    public FeedInspector.Profile profile(@PathVariable UUID id) {
        return inspector.profile(id);
    }

    @GetMapping("/users/{id}/served")
    @Operation(
            operationId = "adminFeedServed",
            summary = "A user's recent feed sessions, newest first, each item with its outcome (positive,"
                    + " negative, furthest watch)")
    public Page<FeedInspector.ServedSession> served(
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(20) Integer limit) {
        return inspector.served(id, cursor, limit);
    }
}
