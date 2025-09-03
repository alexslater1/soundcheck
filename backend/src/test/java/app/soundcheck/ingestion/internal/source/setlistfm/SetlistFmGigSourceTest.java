package app.soundcheck.ingestion.internal.source.setlistfm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.catalog.CatalogIngest.BilledArtist;
import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.pastgigs.SetlistFmBudget;
import app.soundcheck.ingestion.internal.pastgigs.SetlistFmClient;
import app.soundcheck.ingestion.internal.source.CallBudget;
import app.soundcheck.ingestion.internal.source.GigSource;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceIssue;
import app.soundcheck.ingestion.internal.source.SourcePage;
import app.soundcheck.shared.config.JsonMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Which acts a run reads, how far back, when it stops paging, and that an act counts as read
 * only once its last page has been resolved (so a run stopped by its budget resumes with it).
 */
class SetlistFmGigSourceTest {

    static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    static final Instant TODAY = NOW.truncatedTo(ChronoUnit.DAYS);
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    static final BilledArtist KITES =
            new BilledArtist(UUID.randomUUID(), "Pale Kites", UUID.fromString("5b11f4ce-a62d-471e-81fc-a69a8278c7da"));
    static final BilledArtist WOLVES = new BilledArtist(UUID.randomUUID(), "Harbour Wolves", null);

    final JsonMapper json = JsonMappers.builder().build();
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final SetlistFmClient client = mock(SetlistFmClient.class);
    final CatalogIngest catalog = mock(CatalogIngest.class);
    final SetlistFmSyncs syncs = mock(SetlistFmSyncs.class);
    SetlistFmGigSource source;

    @BeforeEach
    void setUp() {
        when(client.configured()).thenReturn(true);
        when(syncs.syncedAt()).thenReturn(Map.of());
        source = source(1400);
    }

    SetlistFmGigSource source(int dailyBudget) {
        IngestionProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                        "soundcheck.ingestion.setlistfm.daily-budget", String.valueOf(dailyBudget),
                        "soundcheck.ingestion.setlistfm.backfill.requests-per-second", "1000",
                        "soundcheck.ingestion.setlistfm.backfill.max-pages-per-artist", "3")))
                .bindOrCreate("soundcheck.ingestion", IngestionProperties.class);
        return new SetlistFmGigSource(
                client, catalog, syncs, new SetlistFmBudget(properties, clock), properties, clock);
    }

    @Test
    void readsTheLastTwoYearsUpToToday() {
        GigSource.Window window = source.window(TODAY, Duration.ofDays(365));

        assertThat(window.from()).isEqualTo(TODAY.minus(Duration.ofDays(730)));
        assertThat(window.to()).isEqualTo(TODAY);
        assertThat(window.upcoming(TODAY)).isFalse();
    }

    @Test
    void pagesAnActUntilItsSetlistsAreOlderThanTheWindowAndMarksItReadAfterTheLastPage() {
        when(catalog.artistsOnGigsWithClips()).thenReturn(List.of(KITES));
        // Page 1: two recent gigs; page 2: one in the window and one older than two years; page 3 is never asked for.
        when(client.artistSetlists(KITES.mbid(), "Pale Kites", 1))
                .thenReturn(page(KITES, 1, 60, "s1", days(10), "s2", days(100)));
        when(client.artistSetlists(KITES.mbid(), "Pale Kites", 2))
                .thenReturn(page(KITES, 2, 60, "s3", days(700), "s4", days(800)));

        Iterator<SourcePage> pages =
                source.pages(from(), TODAY, new CallBudget(100)).iterator();
        SourcePage first = pages.next();
        SourcePage second = pages.next();
        verify(syncs, never()).markSynced(any(), any(), anyInt()); // the last page has not been resolved yet
        assertThat(pages.hasNext()).isFalse();

        assertThat(ids(first)).containsExactly("s1", "s2");
        assertThat(ids(second)).containsExactly("s3");
        assertThat(first.request()).isEqualTo("search/setlists?artistMbid=" + KITES.mbid() + "&countryCode=GB&p=1");
        verify(client, never()).artistSetlists(any(), anyString(), eq(3));
        verify(syncs).markSynced(KITES.id(), NOW, 3);
    }

    @Test
    void actsNeverReadComeFirstAndActsReadThisWeekWait() {
        BilledArtist lastMonth = new BilledArtist(UUID.randomUUID(), "Lantern Choir", null);
        BilledArtist yesterday = new BilledArtist(UUID.randomUUID(), "Static Bloom", null);
        when(catalog.artistsOnGigsWithClips()).thenReturn(List.of(lastMonth, KITES, yesterday, WOLVES));
        when(syncs.syncedAt())
                .thenReturn(Map.of(
                        lastMonth.id(), NOW.minus(Duration.ofDays(30)),
                        yesterday.id(), NOW.minus(Duration.ofDays(1))));
        when(client.artistSetlists(any(), anyString(), anyInt())).thenReturn(null); // 404: none

        List<String> asked = source.pages(from(), TODAY, new CallBudget(100))
                .map(SourcePage::request)
                .toList();

        assertThat(asked)
                .containsExactly(
                        "search/setlists?artistMbid=" + KITES.mbid() + "&countryCode=GB&p=1",
                        "search/setlists?artistName=Harbour Wolves&countryCode=GB&p=1",
                        "search/setlists?artistName=Lantern Choir&countryCode=GB&p=1");
        verify(syncs).markSynced(lastMonth.id(), NOW, 0);
        verify(syncs, never()).markSynced(eq(yesterday.id()), any(), anyInt());
    }

    @Test
    void aLaterReadGoesBackOnlyToTwoWeeksBeforeThePreviousOne() {
        when(catalog.artistsOnGigsWithClips()).thenReturn(List.of(WOLVES));
        when(syncs.syncedAt()).thenReturn(Map.of(WOLVES.id(), NOW.minus(Duration.ofDays(30))));
        when(client.artistSetlists(null, "Harbour Wolves", 1))
                .thenReturn(page(WOLVES, 1, 60, "new", days(5), "overlap", days(40), "known", days(50)));

        List<SourcePage> pages =
                source.pages(from(), TODAY, new CallBudget(100)).toList();

        assertThat(pages).hasSize(1); // the page reached a date before the cutoff: no page 2
        assertThat(ids(pages.getFirst())).containsExactly("new", "overlap");
    }

    @Test
    void aRunOutOfCallsStopsAndLeavesTheActUnreadForTheNextRun() {
        when(catalog.artistsOnGigsWithClips()).thenReturn(List.of(KITES));
        when(client.artistSetlists(any(), anyString(), anyInt()))
                .thenReturn(page(KITES, 1, 60, "s1", days(10)), page(KITES, 2, 60, "s2", days(20)));
        CallBudget budget = new CallBudget(1);

        List<SourcePage> pages = source.pages(from(), TODAY, budget).toList();

        assertThat(pages).hasSize(1);
        assertThat(budget.exhausted()).isTrue(); // the run is PARTIAL
        verify(syncs, never()).markSynced(any(), any(), anyInt());
    }

    @Test
    void theDayAllowanceSharedWithTheAppStopsTheRunWithAnIssue() {
        source = source(1);
        when(catalog.artistsOnGigsWithClips()).thenReturn(List.of(KITES, WOLVES));
        when(client.artistSetlists(any(), anyString(), anyInt())).thenReturn(null);

        List<SourcePage> pages =
                source.pages(from(), TODAY, new CallBudget(100)).toList();

        assertThat(pages).hasSize(2);
        assertThat(pages.get(1).issues()).extracting(SourceIssue::kind).containsExactly("SETLISTFM_DAILY_CAP");
        verify(syncs).markSynced(KITES.id(), NOW, 0);
        verify(syncs, never()).markSynced(eq(WOLVES.id()), any(), anyInt());
    }

    @Test
    void anActWithMorePagesThanTheCapStopsThereWithAnIssue() {
        when(catalog.artistsOnGigsWithClips()).thenReturn(List.of(KITES));
        when(client.artistSetlists(any(), anyString(), anyInt()))
                .thenReturn(
                        page(KITES, 1, 200, "a", days(1)),
                        page(KITES, 2, 200, "b", days(2)),
                        page(KITES, 3, 200, "c", days(3)));

        List<SourcePage> pages =
                source.pages(from(), TODAY, new CallBudget(100)).toList();

        assertThat(pages).hasSize(3);
        assertThat(pages.getLast().issues()).extracting(SourceIssue::kind).containsExactly("SETLISTFM_PAGE_CAP");
        verify(syncs).markSynced(KITES.id(), NOW, 3);
    }

    @Test
    void theStoredPageKeepsNoSongs() {
        when(catalog.artistsOnGigsWithClips()).thenReturn(List.of(KITES));
        when(client.artistSetlists(any(), anyString(), anyInt())).thenReturn(page(KITES, 1, 1, "s1", days(10)));

        SourcePage page =
                source.pages(from(), TODAY, new CallBudget(100)).toList().getFirst();

        assertThat(page.body().toString()).doesNotContain("Opener").contains("s1");
    }

    private static Instant from() {
        return TODAY.minus(Duration.ofDays(730));
    }

    private static LocalDate days(int ago) {
        return LocalDate.ofInstant(TODAY, ZoneOffset.UTC).minusDays(ago);
    }

    private static List<String> ids(SourcePage page) {
        return page.gigs().stream().map(SourceGig::externalId).collect(Collectors.toList());
    }

    /** A setlist.fm page of {@code act}'s setlists: alternating ids and dates, each with one song. */
    private JsonNode page(BilledArtist act, int page, int total, Object... idsAndDates) {
        List<String> setlists = new ArrayList<>();
        for (int i = 0; i < idsAndDates.length; i += 2) {
            setlists.add(
                    """
                    {"id":"%s","eventDate":"%s","url":"https://www.setlist.fm/setlist/x/%s.html",
                     "artist":{%s"name":"%s"},
                     "venue":{"id":"v1","name":"The Snug","city":{"name":"Manchester","coords":{"lat":53.48,"long":-2.24}}},
                     "sets":{"set":[{"song":[{"name":"Opener"}]}]}}"""
                            .formatted(
                                    idsAndDates[i],
                                    DATE.format((LocalDate) idsAndDates[i + 1]),
                                    idsAndDates[i],
                                    act.mbid() == null ? "" : "\"mbid\":\"" + act.mbid() + "\",",
                                    act.name()));
        }
        try {
            return json.readTree("{\"page\":%d,\"itemsPerPage\":20,\"total\":%d,\"setlist\":[%s]}"
                    .formatted(page, total, String.join(",", setlists)));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
