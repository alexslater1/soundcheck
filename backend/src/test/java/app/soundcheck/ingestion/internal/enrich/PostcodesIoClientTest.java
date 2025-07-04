package app.soundcheck.ingestion.internal.enrich;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.catalog.GeoPoint;
import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.source.SourceException;
import app.soundcheck.ingestion.internal.source.SourceRetryConfig;
import app.soundcheck.ingestion.internal.source.TransientSourceException;
import app.soundcheck.shared.config.JsonMappers;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
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

/** The postcode lookup against a fake postcodes.io (WireMock): the bulk request's shape, unknown postcodes, retries. */
@SpringBootTest(
        classes = PostcodesIoClientTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "soundcheck.ingestion.mode=live",
            "soundcheck.ingestion.geocoding.requests-per-second=1000",
            "soundcheck.ingestion.retry.delay-ms=10",
            "soundcheck.ingestion.retry.jitter-ms=0",
            "soundcheck.ingestion.retry.max-delay-ms=50"
        })
class PostcodesIoClientTest {

    @RegisterExtension
    static WireMockExtension api = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void pointAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("soundcheck.ingestion.geocoding.base-url", api::baseUrl);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRetry(proxyTargetClass = true) // as in ResilienceConfig
    @EnableConfigurationProperties(IngestionProperties.class)
    @Import({PostcodesIoClient.class, SourceRetryConfig.class})
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
    PostcodeLookup postcodes;

    // Invented coordinates in postcodes.io's answer shape (no recorded responses are committed).
    static final String ANSWER =
            """
            {"status": 200, "result": [
              {"query": "SG14 1AL", "result": {"postcode": "SG14 1AL", "latitude": 51.79, "longitude": -0.07}},
              {"query": "ZZ99 9ZZ", "result": null}
            ]}""";

    @Test
    void sendsOneBulkRequestForOnlyTheCoordinatesAndLeavesOutUnknownPostcodes() {
        api.stubFor(post(urlPathEqualTo("/postcodes")).willReturn(aResponse().withBody(ANSWER)));

        Map<String, GeoPoint> found = postcodes.locate(List.of("SG14 1AL", "ZZ99 9ZZ"));

        assertThat(found).containsExactly(Map.entry("SG14 1AL", new GeoPoint(51.79, -0.07)));
        api.verify(
                1,
                postRequestedFor(urlPathEqualTo("/postcodes"))
                        .withQueryParam("filter", equalTo("postcode,latitude,longitude"))
                        .withRequestBody(equalToJson("{\"postcodes\": [\"SG14 1AL\", \"ZZ99 9ZZ\"]}")));
    }

    @Test
    void temporaryErrorsAreRetriedAndARefusalIsNot() {
        api.stubFor(post(urlPathEqualTo("/postcodes"))
                .inScenario("busy")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("ok"));
        api.stubFor(post(urlPathEqualTo("/postcodes"))
                .inScenario("busy")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withBody(ANSWER)));

        assertThat(postcodes.locate(List.of("SG14 1AL"))).containsKey("SG14 1AL");
        api.verify(2, postRequestedFor(urlPathEqualTo("/postcodes")));

        api.resetAll();
        api.stubFor(post(urlPathEqualTo("/postcodes")).willReturn(aResponse().withStatus(400)));
        assertThatThrownBy(() -> postcodes.locate(List.of("SG14 1AL")))
                .isInstanceOf(SourceException.class)
                .isNotInstanceOf(TransientSourceException.class);
        api.verify(1, postRequestedFor(urlPathEqualTo("/postcodes")));
    }

    @Test
    void moreThanAHundredPostcodesIsAProgrammingError() {
        List<String> tooMany = java.util.stream.IntStream.range(0, 101)
                .mapToObj(i -> "SG" + i + " 1AL")
                .toList();
        assertThatThrownBy(() -> postcodes.locate(tooMany)).isInstanceOf(IllegalArgumentException.class);
    }
}
