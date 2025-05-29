package app.soundcheck.signals.internal.web;

import app.soundcheck.shared.web.RateLimitProperties;
import app.soundcheck.shared.web.RateLimiter;
import app.soundcheck.signals.internal.EventIntakeService;
import app.soundcheck.signals.internal.EventIntakeService.IncomingEvent;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Client telemetry intake. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "signals", description = "Interaction events from the clients")
public class EventController {

    static final String TYPES =
            "IMPRESSION|VIEW_START|WATCH_PROGRESS|COMPLETE|LIKE|UNLIKE|COMMENT|SHARE|PROFILE_TAP|GIG_TAP|HIDE|REPORT";
    static final String SURFACES = "FOR_YOU|FOLLOWING|PROFILE|GIG|ARTIST|POST|SEARCH";

    private final EventIntakeService intake;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;

    public EventController(EventIntakeService intake, RateLimiter rateLimiter, RateLimitProperties limits) {
        this.intake = intake;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    @PostMapping("/events")
    @Operation(
            operationId = "recordEvents",
            summary = "Record a batch of interaction events (1 to 100); one invalid event rejects the batch")
    public ResponseEntity<Void> record(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "X-Client", required = false) String client,
            @Valid @RequestBody EventsRequest body) {
        UUID userId = UUID.fromString(jwt.getSubject());
        rateLimiter.enforce("events:" + userId, limits.eventsPerMinute(), Duration.ofMinutes(1));
        String platform = "android".equals(client) || "web".equals(client) ? client : null;
        intake.record(
                userId,
                platform,
                body.events().stream()
                        .map(e -> new IncomingEvent(
                                e.type(),
                                e.postId(),
                                e.sessionId(),
                                e.surface(),
                                e.position(),
                                e.value(),
                                e.clientTs()))
                        .toList());
        return ResponseEntity.accepted().build();
    }

    public record EventsRequest(@NotEmpty @Size(max = 100) List<@Valid @NotNull EventIn> events) {}

    public record EventIn(
            @NotNull
                    @Pattern(regexp = TYPES)
                    @Schema(
                            allowableValues = {
                                "IMPRESSION",
                                "VIEW_START",
                                "WATCH_PROGRESS",
                                "COMPLETE",
                                "LIKE",
                                "UNLIKE",
                                "COMMENT",
                                "SHARE",
                                "PROFILE_TAP",
                                "GIG_TAP",
                                "HIDE",
                                "REPORT"
                            })
                    String type,
            @NotNull UUID postId,
            @Schema(nullable = true, description = "The feed session the post was served in, when it came from a feed")
                    UUID sessionId,
            @NotNull
                    @Pattern(regexp = SURFACES)
                    @Schema(allowableValues = {"FOR_YOU", "FOLLOWING", "PROFILE", "GIG", "ARTIST", "POST", "SEARCH"})
                    String surface,
            @Schema(nullable = true) @Min(0) @Max(100_000) Integer position,
            @Schema(nullable = true, description = "Watch fraction for WATCH_PROGRESS (0.25, 0.5, 0.75)")
                    @DecimalMin("0.0")
                    @DecimalMax("1.0")
                    Float value,
            @Schema(nullable = true, description = "When it happened on the device") Instant clientTs) {

        /** A watch-progress event without a fraction says nothing. */
        @JsonIgnore
        @Schema(hidden = true)
        @AssertTrue(message = "value is required for WATCH_PROGRESS")
        public boolean isValuePresentForWatchProgress() {
            return !"WATCH_PROGRESS".equals(type) || value != null;
        }
    }
}
