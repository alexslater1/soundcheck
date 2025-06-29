package app.soundcheck.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * A process that runs only the {@code worker} role (the transcode worker,
 * {@code make worker} on :8090) answers health checks and nothing of the API. The polling loops are
 * switched off here so this context never takes messages from the shared test queue, and the context
 * is closed after the class rather than kept in the cache.
 */
@TestPropertySource(properties = {"soundcheck.roles=worker", "soundcheck.worker.enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkerRoleIT extends IntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void noApiEndpointIsRegistered() {
        assertThat(mappings.getHandlerMethods().keySet())
                .flatMap(info -> info.getPatternValues())
                .noneMatch(pattern -> pattern.startsWith("/api/"));
    }

    @Test
    void healthStillAnswersWhileThePublicApiDoesNot() {
        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");

        // Public paths (no token needed) reach the dispatcher, which has no handler for them any more.
        assertThat(rest.getForEntity("/api/v1/genres", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/v1/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
