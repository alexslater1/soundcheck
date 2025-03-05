package app.soundcheck.ingestion.internal.enrich;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.source.SourceException;
import app.soundcheck.ingestion.internal.source.SourceRetryConfig;
import app.soundcheck.ingestion.internal.source.TransientSourceException;
import app.soundcheck.shared.config.JsonMappers;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
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
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Enrichment clients against fake Last.fm and MusicBrainz servers (WireMock): request shapes, the
 * "not found" and low-score answers, retries on temporary errors, and no key in anything recorded.
 */
@SpringBootTest(
        classes = EnrichmentClientsTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "soundcheck.ingestion.mode=live",
            "soundcheck.ingestion.lastfm.api-key=" + EnrichmentClientsTest.KEY,
            "soundcheck.ingestion.lastfm.requests-per-second=1000",
            "soundcheck.ingestion.musicbrainz.requests-per-second=1000",
            "soundcheck.ingestion.musicbrainz.user-agent=SoundcheckTest/0.1 (test@example.com)",
            "soundcheck.ingestion.retry.delay-ms=10",
            "soundcheck.ingestion.retry.jitter-ms=0",
            "soundcheck.ingestion.retry.max-delay-ms=50"
        })
class EnrichmentClientsTest {

    static final String KEY = "lastfm-fake-key-for-tests";
    static final UUID MBID = UUID.fromString("5b11f4ce-a62d-471e-81fc-a69a8278c7da");

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void pointAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("soundcheck.ingestion.lastfm.base-url", api::baseUrl);
        registry.add("soundcheck.ingestion.musicbrainz.base-url", api::baseUrl);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRetry(proxyTargetClass = true) // as in ResilienceConfig
    @EnableConfigurationProperties(IngestionProperties.class)
    @Import({LastfmClient.class, MusicBrainzClient.class, SourceRetryConfig.class})
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
    ArtistTags lastfm;

    @Autowired
    MbidLookup musicBrainz;

    record Payload(String source, String request) {}

    final List<Payload> recorded = new ArrayList<>();
    final PayloadSink sink = (source, request, body) -> recorded.add(new Payload(source, request));

    // ── Last.fm ─────────────────────────────────────────────────────────────

    @Test
    void lastfmTopTagsByMbid() {
        api.stubFor(
                get(urlPathEqualTo("/2.0/"))
                        .willReturn(
                                json(
                                        """
                {"toptags":{"tag":[{"count":100,"name":"indie rock","url":"https://www.last.fm/tag/indie+rock"},
                                   {"count":41,"name":"britpop","url":"https://www.last.fm/tag/britpop"},
                                   {"count":3,"name":"seen live","url":"https://www.last.fm/tag/seen+live"}],
                            "@attr":{"artist":"Some Act"}}}
                """)));

        List<ArtistTags.Tag> tags = lastfm.topTags(MBID, "Some Act", sink);

        assertThat(tags)
                .containsExactly(
                        new ArtistTags.Tag("indie rock", 100),
                        new ArtistTags.Tag("britpop", 41),
                        new ArtistTags.Tag("seen live", 3));
        api.verify(getRequestedFor(urlPathEqualTo("/2.0/"))
                .withQueryParam("method", equalTo("artist.gettoptags"))
                .withQueryParam("mbid", equalTo(MBID.toString()))
                .withQueryParam("format", equalTo("json"))
                .withQueryParam("api_key", equalTo(KEY)));
        assertThat(recorded).singleElement().satisfies(p -> {
            assertThat(p.source()).isEqualTo("LASTFM");
            assertThat(p.request()).contains("mbid=" + MBID).doesNotContain(KEY).doesNotContain("api_key");
        });
    }

    @Test
    void lastfmTopTagsByNameUsesAutocorrectAndTreatsNotFoundAsEmpty() {
        api.stubFor(get(urlPathEqualTo("/2.0/"))
                .willReturn(json("{\"error\":6,\"message\":\"The artist you supplied could not be found\"}")));

        assertThat(lastfm.topTags(null, "Nobody & The Unknowns", sink)).isEmpty();

        api.verify(getRequestedFor(urlPathEqualTo("/2.0/"))
                .withQueryParam("artist", equalTo("Nobody & The Unknowns"))
                .withQueryParam("autocorrect", equalTo("1")));
        assertThat(recorded).isEmpty(); // nothing worth replaying
    }

    @Test
    void lastfmTemporaryErrorsAreRetriedAndOtherErrorsAreNot() {
        api.stubFor(get(urlPathEqualTo("/2.0/"))
                .inScenario("busy")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(json("{\"error\":29,\"message\":\"Rate limit exceeded\"}"))
                .willSetStateTo("second"));
        api.stubFor(get(urlPathEqualTo("/2.0/"))
                .inScenario("busy")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("ok"));
        api.stubFor(get(urlPathEqualTo("/2.0/"))
                .inScenario("busy")
                .whenScenarioStateIs("ok")
                .willReturn(json("{\"toptags\":{\"tag\":[{\"count\":100,\"name\":\"techno\"}]}}")));

        assertThat(lastfm.topTags(null, "Act", sink)).containsExactly(new ArtistTags.Tag("techno", 100));
        api.verify(3, getRequestedFor(urlPathEqualTo("/2.0/")));

        api.resetAll();
        api.stubFor(get(urlPathEqualTo("/2.0/")).willReturn(json("{\"error\":10,\"message\":\"Invalid API key\"}")));
        assertThatThrownBy(() -> lastfm.topTags(null, "Act", sink))
                .isInstanceOf(SourceException.class)
                .isNotInstanceOf(TransientSourceException.class)
                .hasMessageContaining("error 10")
                .hasMessageNotContaining(KEY);
        api.verify(1, getRequestedFor(urlPathEqualTo("/2.0/")));
    }

    // ── MusicBrainz ─────────────────────────────────────────────────────────

    @Test
    void musicBrainzAcceptsOnlyAHighScoringHitAndIdentifiesItself() {
        api.stubFor(
                get(urlPathEqualTo("/ws/2/artist"))
                        .withQueryParam("query", equalTo("artist:\"Sigur Rós\""))
                        .willReturn(
                                json(
                                        "{\"artists\":[{\"id\":\"" + MBID + "\",\"score\":100,\"name\":\"Sigur Rós\"},"
                                                + "{\"id\":\"00000000-0000-4000-8000-000000000001\",\"score\":62,\"name\":\"Sigur Rós Tribute\"}]}")));
        api.stubFor(get(urlPathEqualTo("/ws/2/artist"))
                .withQueryParam("query", equalTo("artist:\"Half Match\""))
                .willReturn(json("{\"artists\":[{\"id\":\"" + MBID + "\",\"score\":71,\"name\":\"Half Matches\"}]}")));
        api.stubFor(get(urlPathEqualTo("/ws/2/artist"))
                .withQueryParam("query", equalTo("artist:\"The \\\"Quoted\\\" Band\""))
                .willReturn(json("{\"artists\":[]}")));

        assertThat(musicBrainz.findMbid("Sigur Rós", sink)).contains(MBID);
        assertThat(musicBrainz.findMbid("Half Match", sink)).isEmpty(); // below 95
        assertThat(musicBrainz.findMbid("The \"Quoted\" Band", sink)).isEmpty(); // quotes escaped, no hit

        api.verify(
                3,
                getRequestedFor(urlPathEqualTo("/ws/2/artist"))
                        .withQueryParam("fmt", equalTo("json"))
                        .withHeader("User-Agent", equalTo("SoundcheckTest/0.1 (test@example.com)")));
        assertThat(recorded).hasSize(3).allSatisfy(p -> assertThat(p.source()).isEqualTo("MUSICBRAINZ"));
    }

    @Test
    void musicBrainzRetriesWhenRateLimited() {
        api.stubFor(get(urlPathEqualTo("/ws/2/artist"))
                .inScenario("throttle")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("ok"));
        api.stubFor(get(urlPathEqualTo("/ws/2/artist"))
                .inScenario("throttle")
                .whenScenarioStateIs("ok")
                .willReturn(json("{\"artists\":[{\"id\":\"" + MBID + "\",\"score\":98}]}")));

        assertThat(musicBrainz.findMbid("Act", sink)).contains(MBID);
        api.verify(2, getRequestedFor(urlPathEqualTo("/ws/2/artist")));
    }

    private static ResponseDefinitionBuilder json(String body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(body);
    }
}
