package app.soundcheck;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.shared.config.JsonMappers;
import app.soundcheck.support.IntegrationTest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * The OpenAPI contract: {@code contracts/openapi.json} is a canonical (key-sorted, pretty)
 * snapshot of {@code /v3/api-docs}. Normally this test fails when the API drifted from the snapshot;
 * {@code make openapi} runs it with {@code -Dsoundcheck.openapi.write=true} to rewrite the file, then
 * regenerates the TypeScript client with orval.
 */
class OpenApiSnapshotIT extends IntegrationTest {

    static final String WRITE_PROPERTY = "soundcheck.openapi.write";
    static final Path SNAPSHOT = Path.of("..", "contracts", "openapi.json").normalize();

    static final JsonMapper CANONICAL = JsonMappers.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    @Autowired
    TestRestTemplate rest;

    @Test
    void snapshotMatchesTheRunningApi() throws IOException {
        String live = canonical(rest.getForObject("/v3/api-docs", String.class));
        assertThat(live).contains("\"operationId\" : \"health\"");

        if (Boolean.getBoolean(WRITE_PROPERTY)) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, live);
            return;
        }
        assertThat(SNAPSHOT)
                .as("contracts/openapi.json is missing — run `make openapi`")
                .exists();
        assertThat(Files.readString(SNAPSHOT))
                .as("contracts/openapi.json differs from the running API — run `make openapi` and commit the result")
                .isEqualTo(live);
    }

    /**
     * Sorted keys at every level so the snapshot diff only shows real API changes. {@code servers} is
     * dropped: it carries the random test port, and clients use their own base URL.
     */
    static String canonical(String json) {
        try {
            Map<?, ?> tree = CANONICAL.readValue(json, Map.class);
            tree.remove("servers");
            return CANONICAL.writeValueAsString(tree) + System.lineSeparator();
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
