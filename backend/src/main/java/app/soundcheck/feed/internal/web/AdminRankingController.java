package app.soundcheck.feed.internal.web;

import app.soundcheck.feed.internal.AdminRankingConfigs;
import app.soundcheck.feed.internal.AdminRankingConfigs.ConfigView;
import app.soundcheck.feed.internal.RankingConfigs;
import app.soundcheck.feed.internal.RankingPreview;
import app.soundcheck.feed.internal.RankingReplay;
import app.soundcheck.feed.internal.ranking.RankingConfig;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.Actors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ranking playground's API: list, save, edit and activate ranking configs, preview a
 * user's feed under two configs, and replay a config over what was served. Operators only.
 */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1/admin/ranking")
@PreAuthorize(Actors.ADMIN_ONLY)
@Validated
@Tag(name = "admin-ranking", description = "Console: ranking configs, preview and replay (operators only)")
public class AdminRankingController {

    /** A new draft. {@code source}: MANUAL from the console, LAB from the lab's publish (with its metrics). */
    @Schema(name = "CreateRankingConfigRequest")
    public record CreateConfigRequest(
            @NotNull Map<String, Double> weights,
            @NotNull RankingConfig.Params params,
            @Schema(
                            nullable = true,
                            allowableValues = {"MANUAL", "LAB"})
                    AdminRankingConfigs.Source source,
            @Schema(nullable = true) Map<String, Object> metrics,
            @Schema(nullable = true) @Size(max = 2000) String notes) {}

    /** A draft's changes; a part left out stays as it is. */
    @Schema(name = "UpdateRankingConfigRequest")
    public record UpdateConfigRequest(
            @Schema(nullable = true) Map<String, Double> weights,
            @Schema(nullable = true) RankingConfig.Params params,
            @Schema(nullable = true) @Size(max = 2000) String notes) {}

    /** A config by {@code id}, or inline ({@code weights} and {@code params}) as the sliders have it. */
    @Schema(name = "RankingConfigRef")
    public record ConfigRef(
            @Schema(nullable = true) UUID id,
            @Schema(nullable = true) Map<String, Double> weights,
            @Schema(nullable = true) RankingConfig.Params params) {}

    /**
     * Preview one user's feed under {@code configA} and, optionally, {@code configB}.
     *
     * @param exploration off by default, so a weight change isn't mixed up with random injection
     */
    @Schema(name = "RankingPreviewRequest")
    public record PreviewRequest(
            @NotNull UUID userId,
            @NotNull @Valid ConfigRef configA,
            @Schema(nullable = true) @Valid ConfigRef configB,
            @Schema(nullable = true) @Min(1) @Max(50) Integer limit,
            @Schema(nullable = true) Boolean exploration) {}

    /** Replay a config over the items served in [{@code from}, {@code to}), at most 14 days. */
    @Schema(name = "RankingReplayRequest")
    public record ReplayRequest(
            @NotNull @Valid ConfigRef config,
            @NotNull Instant from,
            @NotNull Instant to,
            @Schema(nullable = true) Boolean explorationOnly) {}

    private final AdminRankingConfigs admin;
    private final RankingConfigs configs;
    private final RankingPreview preview;
    private final RankingReplay replay;

    public AdminRankingController(
            AdminRankingConfigs admin, RankingConfigs configs, RankingPreview preview, RankingReplay replay) {
        this.admin = admin;
        this.configs = configs;
        this.preview = preview;
        this.replay = replay;
    }

    @GetMapping("/configs")
    @Operation(operationId = "adminListRankingConfigs", summary = "Every ranking config, newest first")
    public List<ConfigView> list() {
        return admin.list();
    }

    @GetMapping("/configs/{id}")
    @Operation(operationId = "adminGetRankingConfig", summary = "One ranking config")
    public ConfigView get(@PathVariable UUID id) {
        return admin.get(id);
    }

    @PostMapping("/configs")
    @Operation(
            operationId = "adminCreateRankingConfig",
            summary = "Save a DRAFT (validated as for activation); 400 RANKING_CONFIG_INVALID lists"
                    + " every broken rule")
    public ResponseEntity<ConfigView> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateConfigRequest request) {
        ConfigView created = admin.create(
                request.weights(),
                request.params(),
                request.source() == null ? AdminRankingConfigs.Source.MANUAL : request.source(),
                request.metrics(),
                request.notes(),
                UUID.fromString(jwt.getSubject()));
        Actors.audit(jwt, "ranking.config.create", created.id());
        return ResponseEntity.created(URI.create("/api/v1/admin/ranking/configs/" + created.id()))
                .body(created);
    }

    @PatchMapping("/configs/{id}")
    @Operation(
            operationId = "adminUpdateRankingConfig",
            summary = "Edit a DRAFT; 409 CONFIG_NOT_DRAFT for an ACTIVE or ARCHIVED one")
    public ConfigView update(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody UpdateConfigRequest request) {
        ConfigView updated = admin.update(id, request.weights(), request.params(), request.notes());
        Actors.audit(jwt, "ranking.config.update", id);
        return updated;
    }

    @PostMapping("/configs/{id}/activate")
    @Operation(
            operationId = "adminActivateRankingConfig",
            summary = "Make a DRAFT, or an ARCHIVED config (the rollback), the active one; new feed sessions use"
                    + " it. 409 CONFIG_ALREADY_ACTIVE")
    public ConfigView activate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        ConfigView activated = admin.activate(id, UUID.fromString(jwt.getSubject()));
        Actors.audit(jwt, "ranking.config.activate", id);
        return activated;
    }

    @PostMapping("/preview")
    @Operation(
            operationId = "adminPreviewRanking",
            summary = "A user's feed under two configs, side by side, with overlap@10 and Kendall's τ; writes"
                    + " nothing")
    public RankingPreview.Preview preview(@Valid @RequestBody PreviewRequest request) {
        RankingConfig a = resolve(request.configA());
        RankingConfig b = request.configB() == null ? null : resolve(request.configB());
        return preview.preview(
                request.userId(),
                a,
                b,
                request.limit() == null ? 20 : request.limit(),
                Boolean.TRUE.equals(request.exploration()));
    }

    @PostMapping("/replay")
    @Operation(
            operationId = "adminReplayRanking",
            summary = "Re-score what was served in a window (at most 14 days) with a config: AUC, log-loss,"
                    + " NDCG@10, engagement by position. Biased towards the config that served it."
                    + " 422 FEATURE_SCHEMA_MISMATCH")
    public RankingReplay.Result replay(@Valid @RequestBody ReplayRequest request) {
        return replay.replay(
                resolve(request.config()),
                request.from(),
                request.to(),
                Boolean.TRUE.equals(request.explorationOnly()));
    }

    /**
     * A config that breaks the config rules: 400 with every broken rule, so the playground can show
     * them all at once.
     */
    @ExceptionHandler(RankingConfig.InvalidRankingConfigException.class)
    ResponseEntity<ProblemDetail> invalid(RankingConfig.InvalidRankingConfigException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "The ranking config breaks " + e.problems().size() + " rule(s)");
        problem.setType(URI.create("https://soundcheck.app/problems/ranking-config-invalid"));
        problem.setProperty("code", "RANKING_CONFIG_INVALID");
        problem.setProperty(
                "errors",
                e.problems().stream()
                        .map(message -> Map.of("field", "config", "message", message))
                        .toList());
        return ResponseEntity.badRequest().body(problem);
    }

    private RankingConfig resolve(@Nullable ConfigRef ref) {
        return configs.resolve(ref.id(), ref.weights(), ref.params());
    }
}
