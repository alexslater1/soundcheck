package app.soundcheck.content.internal.web;

import app.soundcheck.content.internal.ModerationService;
import app.soundcheck.content.internal.ModerationService.ReportItem;
import app.soundcheck.content.internal.ModerationService.Resolved;
import app.soundcheck.content.internal.PostService;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.Actors;
import app.soundcheck.shared.web.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's moderation page: the queue of reports grouped by target, one
 * decision per target, and restoring a hidden post. Operators only.
 */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize(Actors.ADMIN_ONLY)
@Validated
@Tag(name = "admin-moderation", description = "Console: reports, hidden posts and restores (operators only)")
public class AdminModerationController {

    private final ModerationService moderation;
    private final PostService posts;

    public AdminModerationController(ModerationService moderation, PostService posts) {
        this.moderation = moderation;
        this.posts = posts;
    }

    @GetMapping("/reports")
    @Operation(
            operationId = "adminListReports",
            summary =
                    "Reports grouped by what they report: OPEN (oldest first) or RESOLVED (latest decision" + " first)")
    public Page<ReportItem> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "OPEN")
                    @Schema(allowableValues = {"OPEN", "RESOLVED"})
                    @Pattern(regexp = "OPEN|RESOLVED")
                    String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        return moderation.queue(UUID.fromString(jwt.getSubject()), status.equals("OPEN"), cursor, limit);
    }

    @PostMapping("/reports/{id}/resolve")
    @Operation(
            operationId = "adminResolveReport",
            summary = "Resolve every OPEN report on this report's target: hide the post, delete the comment, or"
                    + " dismiss")
    public Resolved resolve(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ResolveRequest body) {
        Resolved resolved = moderation.resolve(UUID.fromString(jwt.getSubject()), id, body.action());
        Actors.audit(jwt, "moderation." + body.action().toLowerCase(), resolved.kind() + ":" + resolved.id());
        return resolved;
    }

    @PostMapping("/posts/{id}/restore")
    @Operation(
            operationId = "adminRestorePost",
            summary = "Undo a hide: the post is published again (409 POST_REPLACED when its author has posted"
                    + " again for the same gig and day)")
    public ResponseEntity<Void> restore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        posts.restore(id);
        Actors.audit(jwt, "moderation.restore", "POST:" + id);
        return ResponseEntity.noContent().build();
    }

    public record ResolveRequest(
            @NotBlank @Schema(allowableValues = {"HIDE_POST", "DELETE_COMMENT", "DISMISS"}) String action) {}
}
