package app.soundcheck.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The transcode worker as ECS starts it: the {@code aws} profile, only the {@code worker} role,
 * no JWT key anywhere and no {@code OTLP_ENABLED}. It must start, answer its health check, and build no
 * telemetry exporter, so it makes no export calls. (Flyway stays on here: the test database must be
 * migrated whichever test class runs first. The container run starts it with Flyway off.)
 */
@ActiveProfiles("aws") // added to IntegrationTest's "test"
@TestPropertySource(
        properties = {
            "soundcheck.roles=worker",
            "soundcheck.worker.enabled=false",
            "soundcheck.auth.jwt.private-key-pem=",
            "soundcheck.auth.jwt.private-key-path=/nowhere/jwt-es256.pem",
            "soundcheck.media.public-base-url=http://localhost:8088",
            "spring.modulith.events.republish-outstanding-events-on-restart=false"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AwsWorkerIT extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    TestRestTemplate rest;

    @Test
    void startsWithoutAJwtKeyAndAnswersItsHealthCheck() {
        ResponseEntity<String> health = rest.getForEntity("/actuator/health/liveness", String.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void buildsNoTelemetryExporterWhenOtlpIsOff() {
        assertThat(context.getBeanNamesForType(SpanExporter.class)).isEmpty();
        assertThat(context.getBeanNamesForType(LogRecordExporter.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OtlpMeterRegistry.class)).isEmpty();
    }
}
