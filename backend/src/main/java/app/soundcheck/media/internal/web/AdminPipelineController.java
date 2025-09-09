package app.soundcheck.media.internal.web;

import app.soundcheck.media.internal.MediaAdmin;
import app.soundcheck.media.internal.MediaAdmin.PipelineAction;
import app.soundcheck.media.internal.MediaAdmin.PipelineOverview;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.Actors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The media pipeline console's API: the transcode queue and its DLQ, stage timings,
 * failures, and the clips an operator may retry or fail. Operators only.
 */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1/admin/pipeline")
@PreAuthorize(Actors.ADMIN_ONLY)
@Tag(name = "admin-pipeline", description = "Console: the media pipeline, its failures and retries (operators only)")
public class AdminPipelineController {

    private final MediaAdmin admin;

    public AdminPipelineController(MediaAdmin admin) {
        this.admin = admin;
    }

    @GetMapping
    @Operation(
            operationId = "adminPipeline",
            summary = "Queue and DLQ depth, assets by status, stranded and failed clips, recent attempts, and"
                    + " stage percentiles, failures and daily transcode times over [from, to) (default: 7 days)")
    public PipelineOverview overview(
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return admin.overview(from, to);
    }

    @PostMapping("/media/{id}/retry")
    @Operation(
            operationId = "adminPipelineRetry",
            summary = "Send a failed or stranded clip back to the queue with fresh attempts; its failed post"
                    + " waits for it again (409 MEDIA_REJECTED, MEDIA_NOT_RETRYABLE)")
    public PipelineAction retry(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        PipelineAction result = admin.retry(id);
        Actors.audit(jwt, "media.retry", id);
        return result;
    }

    @PostMapping("/media/{id}/fail")
    @Operation(
            operationId = "adminPipelineFail",
            summary = "Give up on a stranded clip: FAILED (reason OPERATOR), and its post fails"
                    + " (409 MEDIA_NOT_STRANDED)")
    public PipelineAction fail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        PipelineAction result = admin.fail(id);
        Actors.audit(jwt, "media.fail", id);
        return result;
    }
}
