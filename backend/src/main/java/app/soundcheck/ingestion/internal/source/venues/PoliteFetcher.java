package app.soundcheck.ingestion.internal.source.venues;

import app.soundcheck.ingestion.internal.IngestionProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Every request to a venue's website goes through here: robots.txt is
 * read first and obeyed (cached for 12 h, so a new {@code Disallow} takes effect by the next daily run),
 * requests to one host are at least {@code minSpacing} apart (more if {@code Crawl-delay} asks), the
 * user agent is our own short, stable one, a 403 or 429 makes us leave the host alone for a day, and
 * nothing is retried: a failure waits for tomorrow. Bodies are capped at 5 MB.
 */
@Component
public class PoliteFetcher {

    private static final Logger log = LoggerFactory.getLogger(PoliteFetcher.class);
    static final Duration ROBOTS_FOR = Duration.ofHours(12);
    static final int MAX_BODY = 5 * 1024 * 1024;

    public enum Outcome {
        OK,
        /** robots.txt does not allow the path for us. */
        DISALLOWED,
        /** The host refused us recently (403 / 429): not asked again until the back-off ends. */
        BLOCKED,
        /** Any other non-2xx answer, a timeout or a network error. */
        FAILED
    }

    public record Result(Outcome outcome, int status, @Nullable String body, String detail) {}

    /** Waits; replaced in tests. */
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private record Robots(RobotsTxt rules, Instant at) {}

    private final RestClient rest;
    private final IngestionProperties.Venues config;
    private final Clock clock;
    private final Sleeper sleeper;
    private final MeterRegistry meters;
    private final Map<String, Robots> robots = new HashMap<>();
    private final Map<String, Instant> lastRequest = new HashMap<>();
    private final Map<String, Instant> blockedUntil = new HashMap<>();

    @Autowired
    public PoliteFetcher(IngestionProperties properties, Clock clock, MeterRegistry meters) {
        this(properties, clock, meters, duration -> Thread.sleep(duration.toMillis()));
    }

    PoliteFetcher(IngestionProperties properties, Clock clock, MeterRegistry meters, Sleeper sleeper) {
        this.config = properties.venues();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
        // Small venues' WordPress sites can take 10+ s to render a cold page (The Dublin Castle: 7.7 s on
        // 2025-06-11, a timeout at 15 s minutes earlier). One request a day can afford to wait.
        requests.setReadTimeout(Duration.ofSeconds(30));
        this.rest = RestClient.builder()
                .requestFactory(requests)
                .defaultHeader("User-Agent", config.userAgent())
                .build();
        this.clock = clock;
        this.sleeper = sleeper;
        this.meters = meters;
    }

    public synchronized Result get(URI url) {
        String host = url.getHost();
        Instant blocked = blockedUntil.get(host);
        if (blocked != null && Instant.now(clock).isBefore(blocked)) {
            return count(
                    new Result(Outcome.BLOCKED, 0, null, host + " refused us recently; left alone until " + blocked));
        }
        RobotsTxt rules = robotsFor(url);
        String path = url.getRawPath() + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery());
        if (!rules.allows(path.isEmpty() ? "/" : path)) {
            return count(new Result(Outcome.DISALLOWED, 0, null, "robots.txt disallows " + path));
        }
        return count(request(url, rules.crawlDelay()));
    }

    private RobotsTxt robotsFor(URI url) {
        String host = url.getHost();
        Robots cached = robots.get(host);
        if (cached != null && Duration.between(cached.at(), Instant.now(clock)).compareTo(ROBOTS_FOR) < 0) {
            return cached.rules();
        }
        URI robotsUrl = URI.create(url.getScheme() + "://" + url.getAuthority() + "/robots.txt");
        Result result = request(robotsUrl, null);
        RobotsTxt rules;
        if (result.outcome() == Outcome.OK && result.body() != null) {
            rules = RobotsTxt.parse(result.body(), productToken());
        } else if (result.status() >= 400
                && result.status() < 500
                && result.status() != 403
                && result.status() != 429) {
            rules = RobotsTxt.allowAll(); // RFC 9309: an unavailable robots.txt (4xx) allows everything
        } else {
            // 5xx, 403/429, network trouble: RFC 9309 says assume complete disallow.
            rules = RobotsTxt.parse("User-agent: *\nDisallow: /\n", productToken());
        }
        robots.put(host, new Robots(rules, Instant.now(clock)));
        return rules;
    }

    private Result request(URI url, @Nullable Duration crawlDelay) {
        String host = url.getHost();
        Duration spacing =
                crawlDelay != null && crawlDelay.compareTo(config.minSpacing()) > 0 ? crawlDelay : config.minSpacing();
        Instant last = lastRequest.get(host);
        if (last != null) {
            Duration wait = spacing.minus(Duration.between(last, Instant.now(clock)));
            if (!wait.isNegative() && !wait.isZero()) {
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Result(Outcome.FAILED, 0, null, "interrupted");
                }
            }
        }
        lastRequest.put(host, Instant.now(clock));
        try {
            return rest.get().uri(url).exchange((request, response) -> {
                int status = response.getStatusCode().value();
                if (status == 403 || status == 429) {
                    blockedUntil.put(host, Instant.now(clock).plus(config.backOff()));
                    log.warn("{} answered {}: leaving it alone for {}", host, status, config.backOff());
                    return new Result(Outcome.BLOCKED, status, null, host + " answered " + status);
                }
                if (status < 200 || status >= 300) {
                    return new Result(Outcome.FAILED, status, null, host + " answered " + status);
                }
                byte[] bytes = response.getBody().readNBytes(MAX_BODY + 1);
                if (bytes.length > MAX_BODY) {
                    return new Result(Outcome.FAILED, status, null, "body over 5 MB");
                }
                return new Result(Outcome.OK, status, new String(bytes, StandardCharsets.UTF_8), "ok");
            });
        } catch (ResourceAccessException e) {
            return new Result(
                    Outcome.FAILED,
                    0,
                    null,
                    "I/O error: " + e.getMostSpecificCause().getClass().getSimpleName());
        }
    }

    /** "SoundcheckBot/0.1 (+…)" → "SoundcheckBot", the token robots.txt groups are matched on. */
    String productToken() {
        String agent = config.userAgent();
        int end = agent.indexOf('/');
        return end > 0 ? agent.substring(0, end) : agent.split("\\s")[0];
    }

    private Result count(Result result) {
        meters.counter(
                        "soundcheck.ingestion.venue.fetches",
                        "outcome",
                        result.outcome().name())
                .increment();
        return result;
    }
}
