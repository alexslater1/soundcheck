package app.soundcheck.ingestion.internal.web;

import app.soundcheck.ingestion.internal.AdminIngestion;
import app.soundcheck.ingestion.internal.AdminIngestion.RunDetail;
import app.soundcheck.ingestion.internal.AdminIngestion.RunSummary;
import app.soundcheck.ingestion.internal.AdminIngestion.SourceView;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.Actors;
import app.soundcheck.shared.web.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's ingestion page: the sources, the run history with stats and issues, and "Run
 * now", which queues a run for a scheduler process instead of running it here. Operators only.
 */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1/admin/ingestion")
@PreAuthorize(Actors.ADMIN_ONLY)
@Validated
@Tag(name = "admin-ingestion", description = "Console: gig-source runs, their issues and \"Run now\" (operators only)")
public class AdminIngestionController {

    @Schema(name = "QueueIngestionRunRequest")
    public record QueueRunRequest(@NotBlank @Size(max = 40) String source) {}

    @Schema(name = "QueuedIngestionRun")
    public record QueuedRun(UUID runId) {}

    private final AdminIngestion admin;

    public AdminIngestionController(AdminIngestion admin) {
        this.admin = admin;
    }

    @GetMapping("/sources")
    @Operation(
            operationId = "adminListIngestionSources",
            summary = "Every gig source, whether this deployment runs it, the mode, and its latest run")
    public List<SourceView> sources() {
        return admin.sources();
    }

    @GetMapping("/runs")
    @Operation(
            operationId = "adminListIngestionRuns",
            summary = "Ingestion runs, newest first, with their main counters and issues counted by kind")
    public Page<RunSummary> runs(
            @RequestParam(required = false) String source,
            @Parameter(schema = @Schema(allowableValues = {"QUEUED", "RUNNING", "SUCCEEDED", "PARTIAL", "FAILED"}))
                    @RequestParam(required = false)
                    String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return admin.list(source, status, cursor, limit);
    }

    @GetMapping("/runs/{id}")
    @Operation(
            operationId = "adminGetIngestionRun",
            summary = "One run with every stat and every stored issue (at most 200). 404 RUN_NOT_FOUND")
    public RunDetail run(@PathVariable UUID id) {
        return admin.get(id);
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            operationId = "adminQueueIngestionRun",
            summary = "\"Run now\": queue a run of a source; a scheduler process claims and runs it within"
                    + " seconds. 400 SOURCE_NOT_ENABLED; 409 INGESTION_ALREADY_RUNNING while the"
                    + " source has a queued or running run")
    public QueuedRun queue(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody QueueRunRequest request) {
        UUID runId = admin.queue(request.source(), UUID.fromString(jwt.getSubject()));
        Actors.audit(jwt, "ingestion.run.queue", request.source() + " " + runId);
        return new QueuedRun(runId);
    }
}
