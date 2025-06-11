package app.soundcheck.ingestion.internal.source.venues;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.source.venues.PoliteFetcher.Outcome;
import app.soundcheck.support.MutableClock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The polite fetcher against a fake venue site: robots.txt first and obeyed, our user agent,
 * spacing between requests (the longer of ours and Crawl-delay), a day's rest after 403, no retries.
 * Time and sleeping are faked: the test records the waits instead of making them.
 */
class PoliteFetcherTest {

    @RegisterExtension
    static WireMockExtension site = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    MutableClock clock = new MutableClock();
    List<Duration> waits = new ArrayList<>();
    PoliteFetcher fetcher;

    @BeforeEach
    void setUp() {
        var source = new MapConfigurationPropertySource(Map.of(
                "soundcheck.ingestion.venues.user-agent", "SoundcheckBot/0.1 (+https://example.com)",
                "soundcheck.ingestion.venues.min-spacing", "5s"));
        IngestionProperties properties =
                new Binder(source).bindOrCreate("soundcheck.ingestion", IngestionProperties.class);
        fetcher = new PoliteFetcher(properties, clock, new SimpleMeterRegistry(), duration -> {
            waits.add(duration);
            clock.advance(duration);
        });
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private URI url(String path) {
        return URI.create(site.baseUrl() + path);
    }

    @Test
    void readsRobotsFirstSendsOurUserAgentAndKeepsFiveSecondsBetweenRequests() {
        site.stubFor(get("/robots.txt").willReturn(aResponse().withBody("User-agent: *\nAllow: /\n")));
        site.stubFor(get("/events").willReturn(aResponse().withBody("<html>events</html>")));

        PoliteFetcher.Result first = fetcher.get(url("/events"));
        PoliteFetcher.Result second = fetcher.get(url("/events"));

        assertThat(first.outcome()).isEqualTo(Outcome.OK);
        assertThat(first.body()).contains("events");
        assertThat(second.outcome()).isEqualTo(Outcome.OK);
        site.verify(1, getRequestedFor(urlEqualTo("/robots.txt"))); // cached
        site.verify(
                2,
                getRequestedFor(urlPathEqualTo("/events"))
                        .withHeader("User-Agent", equalTo("SoundcheckBot/0.1 (+https://example.com)")));
        // robots → events (waits ~5 s) → events (waits ~5 s); the clock runs in real time underneath,
        // so the time the request itself took comes off the wait.
        assertThat(waits).hasSize(2).allSatisfy(wait -> assertThat(wait)
                .isBetween(Duration.ofMillis(4500), Duration.ofSeconds(5)));
    }

    @Test
    void aLongerCrawlDelayWinsAndADisallowedPathIsNeverRequested() {
        site.stubFor(get("/robots.txt")
                .willReturn(aResponse().withBody("User-agent: *\nCrawl-delay: 10\nDisallow: /*?format=json\n")));
        site.stubFor(get(urlPathEqualTo("/events")).willReturn(aResponse().withBody("ok")));

        assertThat(fetcher.get(url("/events?format=json")).outcome()).isEqualTo(Outcome.DISALLOWED);
        assertThat(fetcher.get(url("/events")).outcome()).isEqualTo(Outcome.OK);
        assertThat(fetcher.get(url("/events")).outcome()).isEqualTo(Outcome.OK);

        site.verify(0, getRequestedFor(urlEqualTo("/events?format=json")));
        assertThat(waits.getLast()).isBetween(Duration.ofMillis(9500), Duration.ofSeconds(10));
    }

    @Test
    void aForbiddenAnswerLeavesTheHostAloneForADayAndNothingIsRetried() {
        site.stubFor(get("/robots.txt").willReturn(aResponse().withBody("User-agent: *\nAllow: /\n")));
        site.stubFor(get("/events").willReturn(aResponse().withStatus(403)));

        assertThat(fetcher.get(url("/events")).outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(fetcher.get(url("/events")).outcome()).isEqualTo(Outcome.BLOCKED);
        site.verify(1, getRequestedFor(urlEqualTo("/events")));

        clock.advance(Duration.ofHours(25));
        fetcher.get(url("/events"));
        site.verify(2, getRequestedFor(urlEqualTo("/events")));
    }

    @Test
    void aMissingRobotsFileAllowsEverythingButAnUnreachableOneAllowsNothing() {
        site.stubFor(get("/robots.txt").willReturn(aResponse().withStatus(404)));
        site.stubFor(get("/events").willReturn(aResponse().withBody("ok")));
        assertThat(fetcher.get(url("/events")).outcome()).isEqualTo(Outcome.OK);

        clock.advance(Duration.ofHours(13)); // the robots cache expires
        site.stubFor(get("/robots.txt").willReturn(aResponse().withStatus(503)));
        assertThat(fetcher.get(url("/events")).outcome()).isEqualTo(Outcome.DISALLOWED);
    }

    @Test
    void anUnchangedPageIsAskedForConditionallyAndItsKeptBodyIsReused() {
        site.stubFor(get("/robots.txt").willReturn(aResponse().withBody("User-agent: *\nAllow: /\n")));
        site.stubFor(get("/events")
                .withHeader("If-None-Match", absent())
                .willReturn(aResponse()
                        .withHeader("ETag", "\"v1\"")
                        .withHeader("Last-Modified", "Fri, 02 Oct 2026 08:00:00 GMT")
                        .withBody("<html>the list</html>")));
        site.stubFor(get("/events")
                .withHeader("If-None-Match", equalTo("\"v1\""))
                .willReturn(aResponse().withStatus(304)));

        PoliteFetcher.Result first = fetcher.get(url("/events"));
        PoliteFetcher.Result second = fetcher.get(url("/events"));

        assertThat(first.body()).isEqualTo("<html>the list</html>");
        assertThat(second.outcome()).isEqualTo(Outcome.OK);
        assertThat(second.status()).isEqualTo(304);
        assertThat(second.body()).isEqualTo("<html>the list</html>");
        site.verify(getRequestedFor(urlEqualTo("/events"))
                .withHeader("If-None-Match", equalTo("\"v1\""))
                .withHeader("If-Modified-Since", equalTo("Fri, 02 Oct 2026 08:00:00 GMT")));
    }

    @Test
    void theProductTokenIsTheFirstWordOfTheUserAgent() {
        assertThat(fetcher.productToken()).isEqualTo("SoundcheckBot");
    }
}
