package app.soundcheck.ingestion.internal.web;

import app.soundcheck.ingestion.internal.AdminIngestion;
import app.soundcheck.ingestion.internal.AdminIngestion.RunDetail;
import app.soundcheck.ingestion.internal.AdminIngestion.RunSummary;
import app.soundcheck.ingestion.internal.AdminIngestion.SourceView;
import app.soundcheck.ingestion.internal.AdminMatchReviews;
import app.soundcheck.ingestion.internal.AdminMatchReviews.Decision;
import app.soundcheck.ingestion.internal.AdminMatchReviews.ReviewView;
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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's ingestion page: the sources, the run history with stats and issues, and "Run
 * now", which queues a run for a scheduler process instead of running it here; and the match
 * reviews, listings that almost matched an existing gig. Operators only.
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

    @Schema(name = "MatchReviewDecision")
    public record DecideRequest(
            @NotNull @Schema(description = "MERGE: the listing's gig goes into the existing one; KEEP_APART: two gigs")
                    Decision decision) {}

    private final AdminIngestion admin;
    private final AdminMatchReviews reviews;

    public AdminIngestionController(AdminIngestion admin, AdminMatchReviews reviews) {
        this.admin = admin;
        this.reviews = reviews;
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

    @GetMapping("/match-reviews")
    @Operation(
            operationId = "adminListMatchReviews",
            summary = "Listings that almost matched an existing gig, newest first, with both gigs; reviews whose"
                    + " gig has gone are left out")
    public Page<ReviewView> matchReviews(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(allowableValues = {"OPEN", "MERGED", "KEPT_APART"}))
                    @RequestParam(defaultValue = "OPEN")
                    String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return reviews.list(UUID.fromString(jwt.getSubject()), status, cursor, limit);
    }

    @PostMapping("/match-reviews/{id}/decide")
    @Operation(
            operationId = "adminDecideMatchReview",
            summary = "MERGE (the gig merge) or KEEP_APART. 409 REVIEW_DECIDED; a merge's 409"
                    + " MERGE_CONFLICT leaves the review OPEN")
    public ResponseEntity<Void> decide(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody DecideRequest request) {
        reviews.decide(id, request.decision(), UUID.fromString(jwt.getSubject()));
        Actors.audit(jwt, "ingestion.match-review." + request.decision().name().toLowerCase(java.util.Locale.ROOT), id);
        return ResponseEntity.noContent().build();
    }
}
