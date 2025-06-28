package app.soundcheck.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.shared.config.JsonMappers;
import app.soundcheck.signals.internal.web.EventController.EventIn;
import app.soundcheck.signals.internal.web.EventController.EventsRequest;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

/**
 * The body limit defaults to 1 MB, and the largest body the app really sends, a full batch of 100
 * events with every field at its longest, is far below it (so the limit never refuses telemetry).
 */
class HttpLimitsPropertiesTest {

    @Test
    void theDefaultLimitIsOneMegabyte() {
        HttpLimitsProperties properties = new Binder(new MapConfigurationPropertySource(Collections.emptyMap()))
                .bindOrCreate("soundcheck.http", Bindable.of(HttpLimitsProperties.class));
        assertThat(properties.maxRequestBody()).isEqualTo(DataSize.ofMegabytes(1));
    }

    @Test
    void aFullEventsBatchIsFarBelowTheLimit() throws Exception {
        EventIn longest = new EventIn(
                "WATCH_PROGRESS",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "FOLLOWING",
                100_000,
                0.75f,
                Instant.parse("2026-10-03T23:59:59.999999999Z"));
        EventsRequest batch = new EventsRequest(Collections.nCopies(100, longest));
        int size = JsonMappers.builder().build().writeValueAsBytes(batch).length;
        assertThat(size).isLessThan(64 * 1024);
        assertThat((long) size * 10).isLessThan(DataSize.ofMegabytes(1).toBytes());
    }
}
