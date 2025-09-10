package app.soundcheck.catalog.internal.web;

import app.soundcheck.catalog.internal.FanGigs;
import app.soundcheck.catalog.internal.FanGigs.FanGig;
import app.soundcheck.catalog.internal.GigMerges;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.Actors;
import app.soundcheck.shared.web.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's Catalog page: the queue of gigs fans added, and merging a duplicate gig into
 * the real one. Operators only.
 */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1/admin/catalog")
@PreAuthorize(Actors.ADMIN_ONLY)
@Validated
@Tag(name = "admin-catalog", description = "Console: fan-added gigs and gig merges (operators only)")
public class AdminCatalogController {

    @Schema(name = "GigMergeRequest")
    public record MergeRequest(@NotNull @Schema(description = "The gig that stays") UUID into) {}

    private final FanGigs fanGigs;
    private final GigMerges merges;

    public AdminCatalogController(FanGigs fanGigs, GigMerges merges) {
        this.fanGigs = fanGigs;
        this.merges = merges;
    }

    @GetMapping("/gigs/fan-added")
    @Operation(
            operationId = "adminListFanAddedGigs",
            summary = "Gigs fans added, newest first, with marks, posts and likely duplicates")
    public Page<FanGig> fanAdded(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "false (default): still to review; true: already reviewed")
                    @RequestParam(defaultValue = "false")
                    boolean reviewed,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        return fanGigs.page(adminId(jwt), reviewed, cursor, limit);
    }

    @PostMapping("/gigs/{id}/review")
    @Operation(
            operationId = "adminReviewFanAddedGig",
            summary = "Mark a fan-added gig reviewed (it leaves the queue); 409 GIG_NOT_FAN_ADDED")
    public ResponseEntity<Void> review(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        fanGigs.review(id, adminId(jwt));
        Actors.audit(jwt, "catalog.gig.review", id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/gigs/{id}")
    @Operation(
            operationId = "adminDeleteGig",
            summary = "Delete a gig with no posts and no comments; 409 GIG_HAS_POSTS or GIG_HAS_COMMENTS (merge"
                    + " it instead)")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        fanGigs.delete(id);
        Actors.audit(jwt, "catalog.gig.delete", id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/gigs/{from}/merge-preview")
    @Operation(
            operationId = "adminPreviewGigMerge",
            summary = "Both gigs side by side, what would move, and the conflicts that would refuse the merge")
    public GigMerges.Preview preview(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID from,
            @Parameter(description = "The gig that stays") @RequestParam UUID into) {
        return merges.preview(from, into, adminId(jwt));
    }

    @PostMapping("/gigs/{from}/merge")
    @Operation(
            operationId = "adminMergeGig",
            summary = "Merge the gig into another in one transaction: everything moves, then it is deleted and"
                    + " its id redirects. 409 MERGE_CONFLICT, MERGE_SAME_GIG or GIG_ALREADY_MERGED")
    public GigMerges.Result merge(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID from, @Valid @RequestBody MergeRequest request) {
        GigMerges.Result result = merges.merge(from, request.into(), adminId(jwt));
        Actors.audit(jwt, "catalog.gig.merge", from + " -> " + request.into());
        return result;
    }

    private static UUID adminId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
