package app.soundcheck.ingestion.internal.source;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.source.fixture.FixtureGigSource;
import app.soundcheck.shared.config.JsonMappers;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The fixture source replays the committed synthetic pages through the Ticketmaster mapper, offline. */
class FixtureGigSourceTest {

    @Test
    void replaysEveryCommittedPageInOrderWithoutSpendingBudget() {
        IngestionProperties properties = new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("soundcheck.ingestion", IngestionProperties.class);
        assertThat(properties.live()).isFalse(); // fixtures are the default mode
        FixtureGigSource source =
                new FixtureGigSource(properties, JsonMappers.builder().build());
        CallBudget budget = new CallBudget(10);

        List<SourcePage> pages =
                source.pages(Instant.EPOCH, Instant.EPOCH, budget).toList();

        assertThat(source.name()).isEqualTo("TICKETMASTER");
        assertThat(pages)
                .extracting(SourcePage::request)
                .containsExactly("fixture:page-000.json", "fixture:page-001.json");
        assertThat(pages.stream().mapToInt(p -> p.gigs().size()).sum()).isEqualTo(233);
        assertThat(budget.used()).isZero();
    }
}
