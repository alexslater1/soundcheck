package app.soundcheck.ingestion.internal.source.venues;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.source.CallBudget;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceIssue;
import app.soundcheck.ingestion.internal.source.SourcePage;
import app.soundcheck.ingestion.internal.source.TimePrecision;
import app.soundcheck.shared.config.JsonMappers;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The VENUE source against fake venue sites: only the enabled venues are read, pages are followed,
 * gigs outside the run's window are dropped, a venue Ticketmaster knows is pinned to it, robots.txt
 * refusals and broken sites become issues (never exceptions), and the call budget is respected.
 */
class VenueSiteSourceTest {

    @RegisterExtension
    static WireMockExtension web = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneOffset.UTC);
    static final Instant FROM = Instant.parse("2026-10-02T00:00:00Z");
    static final Instant TO = Instant.parse("2026-12-31T00:00:00Z");

    CatalogIngest catalog = mock(CatalogIngest.class);

    VenueSiteSource source(List<String> enabled, VenueSite... sites) {
        var config = new java.util.HashMap<String, String>(Map.of(
                "soundcheck.ingestion.venues.enabled", "true",
                "soundcheck.ingestion.venues.min-spacing", "0s"));
        for (int i = 0; i < enabled.size(); i++) {
            config.put("soundcheck.ingestion.venues.sites[" + i + "]", enabled.get(i));
        }
        IngestionProperties properties = new Binder(new MapConfigurationPropertySource(config))
                .bindOrCreate("soundcheck.ingestion", IngestionProperties.class);
        JsonMapper json = JsonMappers.builder().build();
        PoliteFetcher fetcher = new PoliteFetcher(properties, CLOCK, new SimpleMeterRegistry(), d -> {});
        return new VenueSiteSource(
                new VenueRegistry(List.of(sites)),
                List.of(new HopeAndAnchorAdapter(json), new MothRssAdapter(), new TribeEventsAdapter(json)),
                fetcher,
                catalog,
                properties,
                json,
                CLOCK);
    }

    VenueSite site(String key, String adapter, String path, String ticketmasterId) {
        return new VenueSite(
                key,
                adapter,
                web.baseUrl() + path,
                new VenueSite.Place(key, "1 Test St", "London", "N1 1AA", 51.5, -0.1),
                ticketmasterId,
                TimePrecision.DOORS,
                null);
    }

    @Test
    void readsOnlyEnabledVenuesFollowsPagesKeepsTheWindowAndPinsToTicketmaster() {
        web.stubFor(get("/robots.txt").willReturn(aResponse().withBody("User-agent: *\nAllow: /\n")));
        web.stubFor(get(urlPathEqualTo("/api/v1/events"))
                .willReturn(aResponse()
                        .withBody(
                                "{\"data\":[{\"id\":1,\"title\":\"Harbour Hounds\",\"date\":\"2026-10-16\",\"doors_time\":\"19:30\",\"status\":\"upcoming\",\"bands\":[]},"
                                        + "{\"id\":2,\"title\":\"Too Late\",\"date\":\"2027-03-01\",\"doors_time\":\"19:30\",\"status\":\"upcoming\",\"bands\":[]}],"
                                        + "\"pagination\":{\"next\":null}}")));
        web.stubFor(get("/rss").willReturn(aResponse().withBody(VenueAdaptersTest.fixture("moth.rss"))));
        UUID tmVenue = UUID.randomUUID();
        when(catalog.venueByExternalId("VENUE", "moth")).thenReturn(Optional.empty());
        when(catalog.venueByExternalId("TICKETMASTER", "KovZTM")).thenReturn(Optional.of(tmVenue));

        List<SourcePage> pages = source(
                        List.of("hope", "moth"),
                        site("hope", "hope-and-anchor-api", "/api/v1/events", null),
                        site("moth", "moth-rss", "/rss", "KovZTM"),
                        site("off", "moth-rss", "/never", null))
                .pages(FROM, TO, new CallBudget(100))
                .toList();

        assertThat(pages).hasSize(2);
        List<SourceGig> gigs = pages.stream().flatMap(p -> p.gigs().stream()).toList();
        assertThat(gigs)
                .extracting(SourceGig::externalId)
                .containsExactlyInAnyOrder(
                        "hope:1",
                        "moth:Femmes-Fatales-Fri-27-Nov-2026",
                        "moth:3b3c-Left-of-The-Dial-warmup-w-Mon-19-Oct-2026",
                        "moth:Cory-Harrison-WANDS-Sun-4-Oct-2026",
                        "moth:K2C-Presents-An-Evening-with-Groom-of-the-Stools-Sat-12-Dec-2026"); // "Too Late" is past
        // the window
        assertThat(gigs).allSatisfy(g -> assertThat(g.source()).isEqualTo("VENUE"));
        assertThat(pages.getFirst().body().path("eventsRead").asInt()).isEqualTo(2);
        assertThat(pages.getFirst().body().path("eventsInWindow").asInt()).isEqualTo(1);
        verify(catalog).linkVenue("VENUE", "moth", tmVenue);
        web.verify(0, getRequestedFor(urlPathEqualTo("/never")));
    }

    @Test
    void aRobotsRefusalOrABrokenPageIsAnIssueAndTheOtherVenuesStillRun() {
        web.stubFor(get("/robots.txt").willReturn(aResponse().withBody("User-agent: *\nDisallow: /private\n")));
        web.stubFor(get("/rss").willReturn(aResponse().withBody("<rss><channel></channel></rss>")));

        List<SourcePage> pages = source(
                        List.of("private", "empty"),
                        site("private", "moth-rss", "/private/rss", null),
                        site("empty", "moth-rss", "/rss", null))
                .pages(FROM, TO, new CallBudget(100))
                .toList();

        assertThat(pages.stream().flatMap(p -> p.issues().stream()).map(SourceIssue::kind))
                .containsExactly("VENUE_DISALLOWED", "VENUE_EMPTY");
        web.verify(0, getRequestedFor(urlPathEqualTo("/private/rss")));
        verify(catalog, never()).linkVenue(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void anExhaustedBudgetStopsFetching() {
        web.stubFor(get("/robots.txt").willReturn(aResponse().withBody("User-agent: *\nAllow: /\n")));
        web.stubFor(get("/rss").willReturn(aResponse().withBody(VenueAdaptersTest.fixture("moth.rss"))));
        CallBudget budget = new CallBudget(0);

        List<SourcePage> pages = source(List.of("moth"), site("moth", "moth-rss", "/rss", null))
                .pages(FROM, TO, budget)
                .toList();

        assertThat(pages).isEmpty();
        assertThat(budget.exhausted()).isTrue();
        web.verify(0, getRequestedFor(urlPathEqualTo("/rss")));
    }
}
