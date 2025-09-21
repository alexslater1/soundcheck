package app.soundcheck.catalog.internal.web;

import app.soundcheck.catalog.internal.FanGigs;
import app.soundcheck.catalog.internal.FanGigs.FanGig;
import app.soundcheck.catalog.internal.GigEdits;
import app.soundcheck.catalog.internal.GigMerges;
import app.soundcheck.catalog.internal.VenueLocations;
import app.soundcheck.catalog.internal.VenueMerges;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.Actors;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's Catalog page: the queue of gigs fans added, merging a duplicate gig
 * or venue into the real one, fans' edits and their undo, and venues' map positions. Operators
 * only.
 */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1/admin/catalog")
@PreAuthorize(Actors.ADMIN_ONLY)
@Validated
@Tag(
        name = "admin-catalog",
        description = "Console: fan-added gigs, gig and venue merges, edit undo, venue locations (operators only)")
public class AdminCatalogController {

    @Schema(name = "GigMergeRequest")
    public record MergeRequest(@NotNull @Schema(description = "The gig that stays") UUID into) {}

    @Schema(name = "VenueMergeRequest")
    public record VenueMergeRequest(@NotNull @Schema(description = "The venue that stays") UUID into) {}

    @Schema(name = "VenueLocationRequest")
    public record LocationRequest(
            @NotNull @Schema(description = "Latitude, WGS84 (the UK is 49.8 to 60.9)") Double lat,
            @NotNull @Schema(description = "Longitude, WGS84 (the UK is -8.7 to 1.8)") Double lng) {}

    private final FanGigs fanGigs;
    private final GigMerges merges;
    private final VenueMerges venueMerges;
    private final GigEdits edits;
    private final VenueLocations locations;

    public AdminCatalogController(
            FanGigs fanGigs, GigMerges merges, VenueMerges venueMerges, GigEdits edits, VenueLocations locations) {
        this.fanGigs = fanGigs;
        this.merges = merges;
        this.venueMerges = venueMerges;
        this.edits = edits;
        this.locations = locations;
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

    @GetMapping("/venues/{from}/merge-preview")
    @Operation(
            operationId = "adminPreviewVenueMerge",
            summary = "Both venues side by side, what would move, and the gigs that would share a night")
    public VenueMerges.Preview previewVenueMerge(
            @PathVariable UUID from, @Parameter(description = "The venue that stays") @RequestParam UUID into) {
        return venueMerges.preview(from, into);
    }

    @PostMapping("/venues/{from}/merge")
    @Operation(
            operationId = "adminMergeVenue",
            summary = "Merge the venue into another in one transaction: gigs, sources' ids, siblings, saved"
                    + " searches and follows move; its id redirects and its name becomes an alias. 409"
                    + " MERGE_CONFLICT, VENUE_MERGE_SAME or VENUE_ALREADY_MERGED")
    public VenueMerges.Result mergeVenue(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID from, @Valid @RequestBody VenueMergeRequest request) {
        VenueMerges.Result result = venueMerges.merge(from, request.into(), adminId(jwt));
        Actors.audit(jwt, "catalog.venue.merge", from + " -> " + request.into());
        return result;
    }

    @GetMapping("/gigs/{id}/edits")
    @Operation(
            operationId = "adminListGigEdits",
            summary = "The gig's edits, newest first: who, field, old and new value, when, and what undid it")
    public List<GigEdits.EditView> gigEdits(@PathVariable UUID id) {
        return edits.history(id);
    }

    @PostMapping("/gig-edits/{id}/undo")
    @Operation(
            operationId = "adminUndoGigEdit",
            summary = "Put the edit's old value back, logged as an edit that undoes it. 409 EDIT_SUPERSEDED,"
                    + " ACT_HAS_CLIPS, MERGE_NOT_UNDOABLE or EDIT_NOT_UNDOABLE")
    public GigEdits.EditView undoGigEdit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        GigEdits.EditView undo = edits.undo(id, adminId(jwt));
        Actors.audit(jwt, "catalog.gig-edit.undo", id);
        return undo;
    }

    @GetMapping("/venues")
    @Operation(
            operationId = "adminListSuspectVenues",
            summary = "Venues whose map position is probably wrong (fan-added, at a town's centre, or none),"
                    + " newest first; a fixed venue leaves the list")
    public Page<VenueLocations.SuspectVenue> suspectVenues(
            @Parameter(description = "Only true is supported: the suspect list") @RequestParam(defaultValue = "true")
                    boolean suspect,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        if (!suspect) {
            throw ApiException.badRequest("SUSPECT_ONLY", "Only the suspect list is available: suspect=true");
        }
        return locations.suspects(cursor, limit);
    }

    @PatchMapping("/venues/{id}")
    @Operation(
            operationId = "adminFixVenueLocation",
            summary = "Put the venue at a point and move its gigs with it. 400 LOCATION_OUTSIDE_UK")
    public VenueLocations.Fixed fixVenueLocation(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody LocationRequest request) {
        VenueLocations.Fixed fixed = locations.fix(id, request.lat(), request.lng(), adminId(jwt));
        Actors.audit(jwt, "catalog.venue.locate", id);
        return fixed;
    }

    private static UUID adminId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
