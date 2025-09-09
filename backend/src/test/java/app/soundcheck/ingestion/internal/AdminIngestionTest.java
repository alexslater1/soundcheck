package app.soundcheck.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * "Run now" is offered for a source only when this deployment's configuration turns it on, read
 * from the same properties as the sources' own conditions (VenueSiteSource, SetlistFmGigSource).
 */
class AdminIngestionTest {

    private static IngestionProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("soundcheck.ingestion", IngestionProperties.class);
    }

    @Test
    void fixturesModeRunsOnlyTicketmaster() {
        IngestionProperties fixtures = bind(Map.of(
                "soundcheck.ingestion.mode", "fixtures",
                "soundcheck.ingestion.venues.enabled", "true",
                "soundcheck.ingestion.setlistfm.backfill.enabled", "true"));
        assertThat(AdminIngestion.enabled("TICKETMASTER", fixtures)).isTrue();
        assertThat(AdminIngestion.enabled("VENUE", fixtures)).isFalse();
        assertThat(AdminIngestion.enabled("SETLISTFM", fixtures)).isFalse();
    }

    @Test
    void liveModeRunsVenuesAndSetlistFmOnlyWhenEachIsSwitchedOn() {
        IngestionProperties liveOnly = bind(Map.of("soundcheck.ingestion.mode", "live"));
        assertThat(AdminIngestion.enabled("TICKETMASTER", liveOnly)).isTrue();
        assertThat(AdminIngestion.enabled("VENUE", liveOnly)).isFalse();
        assertThat(AdminIngestion.enabled("SETLISTFM", liveOnly)).isFalse();

        IngestionProperties everything = bind(Map.of(
                "soundcheck.ingestion.mode", "live",
                "soundcheck.ingestion.venues.enabled", "true",
                "soundcheck.ingestion.setlistfm.backfill.enabled", "true"));
        assertThat(AdminIngestion.enabled("VENUE", everything)).isTrue();
        assertThat(AdminIngestion.enabled("SETLISTFM", everything)).isTrue();
        assertThat(AdminIngestion.enabled("SKIDDLE", everything))
                .as("no adapter yet")
                .isFalse();
    }
}
