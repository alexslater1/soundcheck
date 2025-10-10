package app.soundcheck.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The session settings the API's pool asks Postgres for (application.yaml's {@code data-source-properties}).
 * JIT is off on every connection, so no statement pays for compiling itself.
 */
class DatabaseSessionIT extends IntegrationTest {

    @Autowired
    DSLContext dsl;

    @Test
    void theApiConnectionsRunWithoutJit() {
        assertThat(dsl.fetchValue("show jit", String.class)).isEqualTo("off");
    }
}
