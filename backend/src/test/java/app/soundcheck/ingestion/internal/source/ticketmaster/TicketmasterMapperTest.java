package app.soundcheck.ingestion.internal.source.ticketmaster;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.ingestion.internal.source.SourceArtist;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceIssue;
import app.soundcheck.shared.config.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Mapping from a page in Ticketmaster's response shape to {@code SourceGig}. The pages
 * are the committed synthetic fixtures ({@code ops/scripts/gen_fixtures.py}); the event ids
 * named below are the edge cases that script plants deliberately.
 */
class TicketmasterMapperTest {

    static final Path FIXTURES = Path.of("..", "fixtures", "ticketmaster");

    static List<SourceGig> gigs = new ArrayList<>();
    static List<SourceIssue> issues = new ArrayList<>();
    static Map<String, SourceGig> byId;
    static JsonNode firstPage;

    @BeforeAll
    static void mapBothPages() throws IOException {
        JsonMapper json = JsonMappers.builder().build();
        for (String file : List.of("page-000.json", "page-001.json")) {
            JsonNode page = json.readTree(Files.readAllBytes(FIXTURES.resolve(file)));
            if (firstPage == null) {
                firstPage = page;
            }
            TicketmasterMapper.Mapped mapped = TicketmasterMapper.map(page);
            assertThat(mapped.totalElements()).isEqualTo(240);
            assertThat(mapped.totalPages()).isEqualTo(2);
            gigs.addAll(mapped.gigs());
            issues.addAll(mapped.issues());
        }
        byId = gigs.stream().collect(Collectors.toMap(SourceGig::externalId, Function.identity()));
    }

    @Test
    void mapsEveryEventThatHasADateAVenueAndALineup() {
        // 240 events: 6 without attractions and 1 without a date are skipped, each with an issue.
        assertThat(gigs).hasSize(233);
        assertThat(issues).filteredOn(i -> i.kind().equals("NO_ARTISTS")).hasSize(6);
        assertThat(issues)
                .filteredOn(i -> i.kind().equals("NO_DATE"))
                .singleElement()
                .satisfies(i -> assertThat(i.externalId()).isEqualTo("G5vsyn00075"));
        assertThat(gigs).allSatisfy(g -> {
            assertThat(g.source()).isEqualTo("TICKETMASTER");
            assertThat(g.artists()).isNotEmpty();
            assertThat(g.venue().name()).isNotBlank();
            assertThat(g.ticketUrl()).startsWith("https://tickets.example.com/event/");
        });
    }

    @Test
    void knownTimesComeFromTheUtcDateTime() {
        // First event of the fixture: 18:00 local on 3 Oct 2026 (BST, UTC+1) → 17:00 UTC.
        JsonNode event = firstPage.path("_embedded").path("events").path(0);
        assertThat(event.path("dates").path("start").path("localTime").asText(""))
                .isEqualTo("18:00:00");
        SourceGig gig = byId.get(event.path("id").asText(""));
        assertThat(gig.startsAt()).isEqualTo(Instant.parse("2026-10-03T17:00:00Z"));
    }

    @Test
    void aMissingTimeBecomesHalfPastSevenLocalConvertedToUtc() {
        // G5vsyn00012 has timeTBA and no localTime: 19:30 Europe/London; in early October that is 18:30 UTC.
        SourceGig gig = byId.get("G5vsyn00012");
        assertThat(gig.startsAt().toString()).endsWith("T18:30:00Z");
        assertThat(gig.venue().timezone()).isEqualTo("Europe/London");
    }

    @Test
    void localTimesAreConvertedWithTheZoneInForceOnThatDate() throws Exception {
        // After the clocks go back (25 Oct 2026) 19:30 London is 19:30 UTC; in BST it is 18:30 UTC.
        JsonMapper json = JsonMappers.builder().build();
        JsonNode winter = json.readTree("{\"localDate\":\"2026-11-14\",\"timeTBA\":true}");
        JsonNode summer = json.readTree("{\"localDate\":\"2026-07-14\",\"timeTBA\":true}");
        assertThat(TicketmasterMapper.startsAt(winter, TicketmasterMapper.DEFAULT_ZONE))
                .isEqualTo(Instant.parse("2026-11-14T19:30:00Z"));
        assertThat(TicketmasterMapper.startsAt(summer, TicketmasterMapper.DEFAULT_ZONE))
                .isEqualTo(Instant.parse("2026-07-14T18:30:00Z"));
    }

    @Test
    void theLineupKeepsTicketmastersOrderWithTheHeadlinerFirst() {
        for (JsonNode event : firstPage.path("_embedded").path("events")) {
            SourceGig gig = byId.get(event.path("id").asText(""));
            JsonNode attractions = event.path("_embedded").path("attractions");
            if (gig == null
                    || attractions.size() < 3
                    || event.path("id").asText("").equals("G5vsyn00009")) {
                continue;
            }
            List<String> expected = new ArrayList<>();
            attractions.forEach(a -> expected.add(a.path("id").asText("")));
            assertThat(gig.artists()).extracting(SourceArtist::externalId).containsExactlyElementsOf(expected);
            return;
        }
        throw new AssertionError("the fixture has no event with three or more acts");
    }

    @Test
    void edgeCases() {
        // The same act listed twice on one bill is kept once; the doors time is carried over.
        SourceGig doubled = byId.get("G5vsyn00009");
        assertThat(doubled.artists()).extracting(SourceArtist::externalId).doesNotHaveDuplicates();
        assertThat(doubled.doorsAt()).isEqualTo(doubled.startsAt().minus(Duration.ofMinutes(45)));

        // Two price ranges → lowest minimum and highest maximum.
        SourceGig priced = byId.get("G5vsyn00003");
        assertThat(priced.priceMin()).isEqualByComparingTo(new BigDecimal("22.50"));
        assertThat(priced.priceMax()).isEqualByComparingTo(new BigDecimal("95.00"));
        assertThat(priced.currency()).isEqualTo("GBP");

        assertThat(byId.get("G5vsyn00020").status()).isEqualTo("CANCELLED");
        assertThat(byId.get("G5vsyn00033").status()).isEqualTo("RESCHEDULED");
        assertThat(byId.get("G5vsyn00044").status()).isEqualTo("POSTPONED");
        assertThat(byId.get("G5vsyn00060").status()).isEqualTo("SCHEDULED"); // offsale is still happening

        // Venues: coordinates parsed from strings; two venues have none.
        assertThat(gigs).anySatisfy(g -> assertThat(g.venue().lat()).isNull());
        assertThat(gigs).filteredOn(g -> g.venue().lat() != null).allSatisfy(g -> {
            assertThat(g.venue().lat()).isBetween(49.0, 61.0);
            assertThat(g.venue().lng()).isBetween(-8.5, 2.0);
        });

        // MusicBrainz ids come from the attraction's external links when present.
        long withMbid = gigs.stream()
                .flatMap(g -> g.artists().stream())
                .filter(a -> a.mbid() != null)
                .count();
        assertThat(withMbid).isPositive();
        // "Undefined" is Ticketmaster's placeholder, never a genre.
        assertThat(gigs.stream().flatMap(g -> g.artists().stream()).flatMap(a -> a.genres().stream()))
                .doesNotContain("Undefined");
    }

    @Test
    void placeholderArtworkIsOnlyUsedWhenNothingElseExists() throws Exception {
        JsonMapper json = JsonMappers.builder().build();
        JsonNode page = json.readTree(
                """
                {"_embedded":{"events":[{"id":"E1","name":"Act","url":"https://tickets.example.com/e1",
                  "dates":{"start":{"localDate":"2026-10-03","localTime":"20:00:00","dateTime":"2026-10-03T19:00:00Z"}},
                  "images":[
                    {"ratio":"16_9","url":"https://img.example.com/fallback-1024.jpg","width":1024,"fallback":true},
                    {"ratio":"16_9","url":"https://img.example.com/real-2048.jpg","width":2048,"fallback":false},
                    {"ratio":"16_9","url":"https://img.example.com/real-640.jpg","width":640,"fallback":false},
                    {"ratio":"3_2","url":"https://img.example.com/real-305.jpg","width":305,"fallback":false}],
                  "_embedded":{"venues":[{"id":"V1","name":"Hall"}],"attractions":[{"id":"A1","name":"Act"}]}}]},
                 "page":{"totalElements":1,"totalPages":1}}
                """);
        SourceGig gig = TicketmasterMapper.map(page).gigs().getFirst();
        assertThat(gig.imageUrl()).isEqualTo("https://img.example.com/real-640.jpg");
        assertThat(gig.artists().getFirst().imageUrl()).isNull();
    }
}
