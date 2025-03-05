package app.soundcheck.ingestion.internal.source.ticketmaster;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.source.CallBudget;
import app.soundcheck.ingestion.internal.source.SourceException;
import app.soundcheck.ingestion.internal.source.SourcePage;
import app.soundcheck.ingestion.internal.source.SourceRetryConfig;
import app.soundcheck.ingestion.internal.source.TransientSourceException;
import app.soundcheck.shared.config.JsonMappers;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * The Ticketmaster source against a fake Ticketmaster (WireMock): paging, the window split when a week reports
 * more than 1,000 events, 429 then success honouring {@code Retry-After}, retries running out, budget
 * exhaustion, and the API key never appearing in recorded requests or error messages.
 *
 * <p>The context is only the client, the source and Spring's resilience proxies (no database), because
 * {@code @Retryable} only works through the proxy.
 */
@SpringBootTest(
        classes = TicketmasterSourceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "soundcheck.ingestion.mode=live",
            "soundcheck.ingestion.ticketmaster.api-key=" + TicketmasterSourceTest.KEY,
            "soundcheck.ingestion.ticketmaster.requests-per-second=1000",
            "soundcheck.ingestion.retry.delay-ms=10",
            "soundcheck.ingestion.retry.jitter-ms=0",
            "soundcheck.ingestion.retry.max-delay-ms=50"
        })
class TicketmasterSourceTest {

    static final String KEY = "test-key-that-must-never-leak-0123456789";
    static final String PATH = "/discovery/v2/events.json";
    static final Instant FROM = Instant.parse("2026-10-03T00:00:00Z");
    static final Instant WEEK = FROM.plus(Duration.ofDays(7));

    @RegisterExtension
    static WireMockExtension ticketmaster = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void pointAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("soundcheck.ingestion.ticketmaster.base-url", ticketmaster::baseUrl);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRetry(proxyTargetClass = true) // as in ResilienceConfig
    @EnableConfigurationProperties(IngestionProperties.class)
    @Import({TicketmasterClient.class, TicketmasterGigSource.class, SourceRetryConfig.class})
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
    TicketmasterGigSource source;

    @Test
    void followsPagesUntilTheLast() {
        stubPage("0", page(3, 450, 0));
        stubPage("1", page(3, 450, 1));
        stubPage("2", page(3, 450, 2));
        CallBudget budget = new CallBudget(100);

        List<SourcePage> pages = source.pages(FROM, WEEK, budget).toList();

        assertThat(pages).hasSize(3);
        assertThat(pages).allSatisfy(p -> assertThat(p.gigs()).hasSize(1));
        assertThat(pages).extracting(p -> p.gigs().getFirst().externalId()).containsExactly("E0", "E1", "E2");
        assertThat(budget.used()).isEqualTo(3);
        assertThat(budget.exhausted()).isFalse();
        ticketmaster.verify(
                3,
                getRequestedFor(urlPathEqualTo(PATH))
                        .withQueryParam("countryCode", equalTo("GB"))
                        .withQueryParam("classificationName", equalTo("music"))
                        .withQueryParam("size", equalTo("200"))
                        .withQueryParam("sort", equalTo("date,asc"))
                        .withQueryParam("startDateTime", equalTo("2026-10-03T00:00:00Z"))
                        .withQueryParam("endDateTime", equalTo("2026-10-09T23:59:59Z"))
                        .withQueryParam("apikey", equalTo(KEY)));
    }

    @Test
    void aWeekOverThePagingCapIsRequeriedDayByDay() {
        // The week reports 1,500 events (more than the API will page through) → seven day queries instead.
        ticketmaster.stubFor(get(urlPathEqualTo(PATH))
                .withQueryParam("endDateTime", equalTo("2026-10-09T23:59:59Z"))
                .willReturn(json(page(8, 1500, 0))));
        ticketmaster.stubFor(get(urlPathEqualTo(PATH))
                .withQueryParam(
                        "endDateTime",
                        com.github.tomakehurst.wiremock.client.WireMock.matching("2026-10-0[3-9]T23:59:59Z"))
                .atPriority(1)
                .willReturn(json(page(1, 150, 0))));
        // The Friday (9 Oct) day window ends at the same second as the week; tell them apart by the start.
        ticketmaster.stubFor(get(urlPathEqualTo(PATH))
                .withQueryParam("startDateTime", equalTo("2026-10-03T00:00:00Z"))
                .withQueryParam("endDateTime", equalTo("2026-10-09T23:59:59Z"))
                .atPriority(0)
                .willReturn(json(page(8, 1500, 0))));
        CallBudget budget = new CallBudget(100);

        List<SourcePage> pages = source.pages(FROM, WEEK, budget).toList();

        // 1 discarded week query + 7 day queries; no page 1 of the oversized week was requested.
        assertThat(pages).hasSize(7);
        assertThat(budget.used()).isEqualTo(8);
        ticketmaster.verify(0, getRequestedFor(urlPathEqualTo(PATH)).withQueryParam("page", equalTo("1")));
        for (String day : List.of("03", "04", "05", "06", "07", "08", "09")) {
            ticketmaster.verify(
                    1,
                    getRequestedFor(urlPathEqualTo(PATH))
                            .withQueryParam("startDateTime", equalTo("2026-10-" + day + "T00:00:00Z"))
                            .withQueryParam("endDateTime", equalTo("2026-10-" + day + "T23:59:59Z")));
        }
    }

    @Test
    void aDayOverThePagingCapIsRequeriedPerMarketAndGapsAreReported() {
        Instant day = FROM.plus(Duration.ofDays(1));
        // One-day horizon: the "week" window and the day window are the same day, both oversized.
        ticketmaster.stubFor(get(urlPathEqualTo(PATH)).atPriority(5).willReturn(json(page(6, 1100, 0))));
        ticketmaster.stubFor(get(urlPathEqualTo(PATH))
                .withQueryParam("marketId", com.github.tomakehurst.wiremock.client.WireMock.matching("\\d+"))
                .atPriority(1)
                .willReturn(json(page(1, 130, 0))));

        List<SourcePage> pages = source.pages(FROM, day, new CallBudget(100)).toList();

        // Eight UK markets, one page each; 8 × 130 = 1,040 < 1,100 → the shortfall is reported.
        assertThat(pages).hasSize(8);
        for (String market : List.of("202", "203", "204", "205", "206", "207", "208", "209")) {
            ticketmaster.verify(1, getRequestedFor(urlPathEqualTo(PATH)).withQueryParam("marketId", equalTo(market)));
        }
        assertThat(pages.getLast().issues()).singleElement().satisfies(issue -> assertThat(issue.kind())
                .isEqualTo("MARKET_SPLIT_INCOMPLETE"));
    }

    @Test
    void a429IsRetriedAfterTheRetryAfterDelay() {
        ticketmaster.stubFor(get(urlPathEqualTo(PATH))
                .inScenario("throttle")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1"))
                .willSetStateTo("recovered"));
        ticketmaster.stubFor(get(urlPathEqualTo(PATH))
                .inScenario("throttle")
                .whenScenarioStateIs("recovered")
                .willReturn(json(page(1, 1, 0))));
        CallBudget budget = new CallBudget(100);

        long started = System.nanoTime();
        List<SourcePage> pages = source.pages(FROM, WEEK, budget).toList();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(pages).hasSize(1);
        assertThat(budget.used()).isEqualTo(2); // both attempts count against the budget
        assertThat(took).isGreaterThanOrEqualTo(Duration.ofSeconds(1)); // Retry-After: 1 was honoured
    }

    @Test
    void serverErrorsAreRetriedFourTimesThenSurface() {
        ticketmaster.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)));
        CallBudget budget = new CallBudget(100);

        assertThatThrownBy(() -> source.pages(FROM, WEEK, budget).toList())
                .isInstanceOf(TransientSourceException.class)
                .hasMessageContaining("503")
                .hasMessageNotContaining(KEY);
        assertThat(budget.used()).isEqualTo(5); // 1 call + 4 retries
    }

    @Test
    void clientErrorsAreNotRetriedAndIoErrorsNeverQuoteTheUrl() {
        ticketmaster.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)));
        CallBudget budget = new CallBudget(100);
        assertThatThrownBy(() -> source.pages(FROM, WEEK, budget).toList())
                .isInstanceOf(SourceException.class)
                .isNotInstanceOf(TransientSourceException.class)
                .hasMessageContaining("401")
                .hasMessageNotContaining(KEY);
        assertThat(budget.used()).isEqualTo(1);

        ticketmaster.resetAll();
        ticketmaster.stubFor(
                get(urlPathEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThatThrownBy(() -> source.pages(FROM, WEEK, new CallBudget(100)).toList())
                .isInstanceOf(TransientSourceException.class)
                .hasMessageNotContaining(KEY)
                .hasNoCause();
    }

    @Test
    void theStreamStopsWhenTheBudgetIsSpent() {
        stubPage("0", page(3, 450, 0));
        stubPage("1", page(3, 450, 1));
        stubPage("2", page(3, 450, 2));
        CallBudget budget = new CallBudget(2);

        List<SourcePage> pages = source.pages(FROM, WEEK, budget).toList();

        assertThat(pages).hasSize(2);
        assertThat(budget.used()).isEqualTo(2);
        assertThat(budget.exhausted()).isTrue(); // the run records PARTIAL
    }

    @Test
    void recordedRequestsNeverContainTheKey() {
        stubPage("0", page(1, 1, 0));

        SourcePage page = source.pages(FROM, WEEK, new CallBudget(10)).toList().getFirst();

        assertThat(page.request())
                .startsWith(PATH + "?classificationName=music&countryCode=GB")
                .doesNotContain("apikey")
                .doesNotContain(KEY);
    }

    private static void stubPage(String number, String body) {
        ticketmaster.stubFor(get(urlPathEqualTo(PATH))
                .withQueryParam("page", equalTo(number))
                .willReturn(json(body)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(body);
    }

    /** A minimal Ticketmaster-shaped page holding one mappable event {@code E<number>}. */
    private static String page(int totalPages, int totalElements, int number) {
        return """
                {"_embedded":{"events":[{"id":"E%d","name":"Act %d","url":"https://tickets.example.com/e%d",
                  "dates":{"start":{"localDate":"2026-10-03","localTime":"20:00:00","dateTime":"2026-10-03T19:00:00Z"},
                           "timezone":"Europe/London","status":{"code":"onsale"}},
                  "_embedded":{"venues":[{"id":"V1","name":"Hall","city":{"name":"Leeds"}}],
                               "attractions":[{"id":"A%d","name":"Act %d"}]}}]},
                 "page":{"size":200,"totalElements":%d,"totalPages":%d,"number":%d}}
                """
                .formatted(number, number, number, number, number, totalElements, totalPages, number);
    }
}
