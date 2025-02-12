package app.soundcheck.shared.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public liveness-style endpoint for clients and smoke tests. Deep checks (DB, S3, SQS)
 * are on {@code /actuator/health/readiness}, which the load balancer uses.
 */
@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "health", description = "Service health")
public class HealthController {

    private final Clock clock;
    private final String version;

    public HealthController(Clock clock, Optional<BuildProperties> buildProperties) {
        this.clock = clock;
        this.version = buildProperties.map(BuildProperties::getVersion).orElse("dev");
    }

    @GetMapping
    @Operation(
            operationId = "health",
            summary = "API health",
            description = "Returns UP when the API process is serving.")
    public HealthResponse health() {
        return new HealthResponse("UP", version, Instant.now(clock));
    }

    /** Health payload. */
    public record HealthResponse(String status, String version, Instant time) {}
}
