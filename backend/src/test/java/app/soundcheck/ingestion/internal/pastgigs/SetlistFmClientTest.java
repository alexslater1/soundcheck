package app.soundcheck.ingestion.internal.pastgigs;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.source.TransientSourceException;
import app.soundcheck.shared.config.JsonMappers;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The setlist.fm client against a fake setlist.fm (WireMock): the request shape (key in a header, UK only, the name
 * percent-encoded), parsing of the documented response, "no results" both ways, and no key in errors.
 */
@SpringBootTest(
        classes = SetlistFmClientTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "soundcheck.ingestion.setlistfm.api-key=" + SetlistFmClientTest.KEY,
            "soundcheck.ingestion.setlistfm.requests-per-second=1000"
        })
class SetlistFmClientTest {

    static final String KEY = "setlistfm-fake-key-for-tests";

    /** Shaped like the documented response (api.setlist.fm/docs, search/setlists), two entries. */
    static final String BODY =
            """
            {"type":"setlists","itemsPerPage":20,"page":1,"total":2,"setlist":[
              {"id":"63d6a2b3","versionId":"v1","eventDate":"17-10-2025",
               "url":"https://www.setlist.fm/setlist/pale-kites/2025/the-snug-manchester-england-63d6a2b3.html",
               "artist":{"mbid":"5b11f4ce-a62d-471e-81fc-a69a8278c7da","name":"Pale Kites","sortName":"Pale Kites"},
               "venue":{"id":"v1","name":"The Snug","city":{"name":"Manchester","state":"England",
                 "coords":{"lat":53.48,"long":-2.24},"country":{"code":"GB","name":"United Kingdom"}}},
               "tour":{"name":"Autumn Tour"},"sets":{"set":[]}},
              {"id":"broken","eventDate":"not a date","artist":{"name":"Pale Kites"},"venue":{"name":"X"}}
            ]}
            """;

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void pointAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("soundcheck.ingestion.setlistfm.base-url", api::baseUrl);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IngestionProperties.class)
    @Import(SetlistFmClient.class)
    static class Config {

        @Bean
        JsonMapper jsonMapper() {
            return JsonMappers.builder().build();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    SetlistFmClient client;

    @Test
    void searchesUkSetlistsByArtistWithTheKeyInAHeaderAndParsesThem() {
        api.stubFor(get(urlPathEqualTo(SetlistFmClient.PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(BODY)));

        PastGigPage page = client.search("Florence + The Machine", 2025, 1);

        api.verify(getRequestedFor(urlPathEqualTo(SetlistFmClient.PATH))
                .withHeader("x-api-key", equalTo(KEY))
                .withHeader("Accept", equalTo("application/json"))
                .withQueryParam("artistName", equalTo("Florence + The Machine"))
                .withQueryParam("countryCode", equalTo("GB"))
                .withQueryParam("year", equalTo("2025"))
                .withQueryParam("p", equalTo("1")));
        assertThat(api.getAllServeEvents().getFirst().getRequest().getUrl())
                .contains("artistName=Florence%20%2B%20The%20Machine")
                .doesNotContain(KEY);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(1); // the entry without a readable date is skipped
        PastGig gig = page.items().getFirst();
        assertThat(gig.setlistId()).isEqualTo("63d6a2b3");
        assertThat(gig.date()).isEqualTo(LocalDate.of(2025, 10, 17));
        assertThat(gig.artistName()).isEqualTo("Pale Kites");
        assertThat(gig.artistMbid()).isEqualTo("5b11f4ce-a62d-471e-81fc-a69a8278c7da");
        assertThat(gig.venueName()).isEqualTo("The Snug");
        assertThat(gig.cityName()).isEqualTo("Manchester");
        assertThat(gig.lat()).isEqualTo(53.48);
        assertThat(gig.lng()).isEqualTo(-2.24);
        assertThat(gig.tour()).isEqualTo("Autumn Tour");
        assertThat(gig.url()).startsWith("https://www.setlist.fm/setlist/");
    }

    @Test
    void noResultsIsAnEmptyPageWhetherSetlistFmSays404OrAnEmptyList() {
        api.stubFor(get(urlPathEqualTo(SetlistFmClient.PATH))
                .willReturn(aResponse().withStatus(404).withBody("{\"code\":404,\"message\":\"not found\"}")));
        assertThat(client.search("Nobody At All", null, 1).items()).isEmpty();
        assertThat(api.getAllServeEvents()
                        .getFirst()
                        .getRequest()
                        .queryParameter("year")
                        .isPresent())
                .isFalse();

        api.stubFor(get(urlPathEqualTo(SetlistFmClient.PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"setlist\":[],\"total\":0,\"page\":1,\"itemsPerPage\":20}")));
        assertThat(client.search("Nobody At All", null, 1).items()).isEmpty();
    }

    @Test
    void rateLimitAndServerErrorsAreTemporaryAndNeverMentionTheKey() {
        api.stubFor(
                get(urlPathEqualTo(SetlistFmClient.PATH)).willReturn(aResponse().withStatus(429)));
        assertThatThrownBy(() -> client.search("Pale Kites", null, 1))
                .isInstanceOf(TransientSourceException.class)
                .hasMessageNotContaining(KEY);
    }

    static final String SETLIST =
            """
            {"id":"7bd6a2a8","url":"https://www.setlist.fm/setlist/pale-kites/2025/the-snug-7bd6a2a8.html",
             "eventDate":"17-10-2025","artist":{"name":"Pale Kites"},
             "venue":{"name":"The Snug","city":{"name":"Manchester"}},"tour":{"name":"Autumn Tour"},
             "sets":{"set":[
               {"song":[{"name":"Intro","tape":true},{"name":"Harbour Lights"},
                        {"name":"Paper Moon","cover":{"name":"Night Bus"}},{"name":""}]},
               {"name":"Acoustic","song":[{"name":"Small Hours","info":"solo"}]},
               {"song":[]},
               {"encore":1,"song":[{"name":"Last Train"}]}
             ]}}
            """;

    @Test
    void findsTheSetlistsOfAnActOnADayByMusicBrainzIdAndReadsTheSongs() {
        // The songs, set by set, with covers, tapes and notes.
        api.stubFor(get(urlPathEqualTo(SetlistFmClient.PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"setlist\":[" + SETLIST + "]}")));

        List<Setlist> found = client.setlistsOn(
                UUID.fromString("5b11f4ce-a62d-471e-81fc-a69a8278c7da"), "Pale Kites", LocalDate.of(2025, 10, 17));

        api.verify(getRequestedFor(urlPathEqualTo(SetlistFmClient.PATH))
                .withHeader("x-api-key", equalTo(KEY))
                .withQueryParam("artistMbid", equalTo("5b11f4ce-a62d-471e-81fc-a69a8278c7da"))
                .withQueryParam("countryCode", equalTo("GB"))
                .withQueryParam("date", equalTo("17-10-2025")));
        assertThat(api.getAllServeEvents()
                        .getFirst()
                        .getRequest()
                        .queryParameter("artistName")
                        .isPresent())
                .isFalse();
        Setlist setlist = found.getFirst();
        assertThat(setlist.url()).startsWith("https://www.setlist.fm/setlist/");
        assertThat(setlist.city()).isEqualTo("Manchester");
        assertThat(setlist.tour()).isEqualTo("Autumn Tour");
        // The empty set and the nameless song are dropped; the encore is named.
        assertThat(setlist.sets()).extracting(Setlist.SetlistPart::title).containsExactly(null, "Acoustic", "Encore");
        assertThat(setlist.sets().getFirst().songs())
                .containsExactly(
                        new Setlist.SetlistSong("Intro", null, null, true),
                        new Setlist.SetlistSong("Harbour Lights", null, null, false),
                        new Setlist.SetlistSong("Paper Moon", null, "Night Bus", false));
        assertThat(setlist.sets().get(1).songs().getFirst().info()).isEqualTo("solo");
    }

    @Test
    void readsOneSetlistByIdAndSearchesByNameWithoutAnId() {
        api.stubFor(get(urlPathEqualTo(SetlistFmClient.SETLIST_PATH + "/7bd6a2a8"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(SETLIST)));
        assertThat(client.setlist("7bd6a2a8")).map(Setlist::id).contains("7bd6a2a8");

        api.stubFor(
                get(urlPathEqualTo(SetlistFmClient.PATH)).willReturn(aResponse().withStatus(404)));
        assertThat(client.setlistsOn(null, "Pale Kites", LocalDate.of(2025, 10, 17)))
                .isEmpty();
        api.verify(getRequestedFor(urlPathEqualTo(SetlistFmClient.PATH))
                .withQueryParam("artistName", equalTo("Pale Kites"))
                .withQueryParam("date", equalTo("17-10-2025")));
    }
}
