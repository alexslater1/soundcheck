package app.soundcheck.ingestion.internal.source;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.source.fixture.FixtureGigSource;
import app.soundcheck.shared.config.JsonMappers;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The fixture source replays the committed synthetic pages through the Ticketmaster mapper, offline. */
class FixtureGigSourceTest {

    static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static List<SourcePage> pagesOn(String today) {
        IngestionProperties properties = new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("soundcheck.ingestion", IngestionProperties.class);
        assertThat(properties.live()).isFalse(); // fixtures are the default mode
        Clock clock = Clock.fixed(Instant.parse(today + "T08:00:00Z"), ZoneOffset.UTC);
        FixtureGigSource source =
                new FixtureGigSource(properties, JsonMappers.builder().build(), clock);
        assertThat(source.name()).isEqualTo("TICKETMASTER");
        assertThat(source.synthetic()).isTrue(); // the committed pages carry "synthetic": true
        CallBudget budget = new CallBudget(10);
        List<SourcePage> pages =
                source.pages(Instant.EPOCH, Instant.EPOCH, budget).toList();
        assertThat(budget.used()).isZero();
        return pages;
    }

    @Test
    void replaysEveryCommittedPageInOrderWithoutSpendingBudget() {
        // The fixture's first day is 3 Oct 2026: on that day nothing is shifted.
        List<SourcePage> pages = pagesOn("2026-10-03");

        assertThat(pages)
                .extracting(SourcePage::request)
                .containsExactly("fixture:page-000.json", "fixture:page-001.json");
        assertThat(pages.stream().mapToInt(p -> p.gigs().size()).sum()).isEqualTo(233);
        assertThat(pages.stream().flatMap(p -> p.issues().stream()).map(SourceIssue::kind))
                .doesNotContain("FIXTURE_DATES_SHIFTED");
    }

    @Test
    void datesMoveForwardSoTheFirstRecordedDayIsTodayAndLocalTimesSurviveTheClockChange() {
        Map<String, SourceGig> recorded = pagesOn("2026-10-03").stream()
                .flatMap(p -> p.gigs().stream())
                .collect(Collectors.toMap(SourceGig::externalId, Function.identity()));

        // 20 Nov 2026 is 48 days later and on the other side of the change from BST to GMT (25 Oct).
        List<SourcePage> pages = pagesOn("2026-11-20");

        List<SourceGig> shifted = pages.stream().flatMap(p -> p.gigs().stream()).toList();
        assertThat(shifted).hasSize(233);
        assertThat(shifted).allSatisfy(gig -> {
            SourceGig original = recorded.get(gig.externalId());
            assertThat(gig.startsAt().atZone(LONDON).toLocalDate())
                    .isEqualTo(original.startsAt().atZone(LONDON).toLocalDate().plusDays(48));
            assertThat(gig.startsAt().atZone(LONDON).toLocalTime())
                    .isEqualTo(original.startsAt().atZone(LONDON).toLocalTime());
        });
        assertThat(shifted.stream()
                        .map(g -> g.startsAt().atZone(LONDON).toLocalDate())
                        .min(java.time.LocalDate::compareTo))
                .contains(java.time.LocalDate.parse("2026-11-20"));
        // The time-TBA gig still starts at 19:30 local, which is 19:30 UTC in winter.
        SourceGig tba = shifted.stream()
                .filter(g -> g.externalId().equals("G5vsyn00012"))
                .findFirst()
                .orElseThrow();
        assertThat(tba.startsAt().atZone(LONDON).toLocalTime()).isEqualTo(LocalTime.of(19, 30));
        assertThat(tba.startsAt().toString()).endsWith("T19:30:00Z");
        assertThat(pages.getFirst().issues())
                .anySatisfy(issue -> assertThat(issue.kind()).isEqualTo("FIXTURE_DATES_SHIFTED"));
    }
}
