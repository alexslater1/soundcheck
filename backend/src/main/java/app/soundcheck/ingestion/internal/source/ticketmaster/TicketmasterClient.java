package app.soundcheck.ingestion.internal.source.ticketmaster;

import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.source.CallBudget;
import app.soundcheck.ingestion.internal.source.SourceException;
import app.soundcheck.ingestion.internal.source.TransientSourceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The only class that talks HTTP to the Ticketmaster Discovery API: one call at a time, at
 * most {@code requestsPerSecond} per second, retried on 429/5xx/I-O errors.
 *
 * <p>The API key travels as a query parameter, so three things keep it out of sight: this client is built
 * without Spring's request observations (their spans would carry the full URL), every message and the
 * recorded request string are built from the parameters <em>without</em> the key, and I/O exceptions are
 * replaced rather than wrapped (their messages quote the URL).
 *
 * <p>{@code @Retryable} (retries and the concurrency limit) works through the Spring proxy, which is why this is a
 * separate bean from {@link TicketmasterGigSource}: a call from inside the same object would skip it.
 */
@Component
public class TicketmasterClient {

    static final String EVENTS_PATH = "/discovery/v2/events.json";

    private final RestClient rest;
    private final JsonMapper json;
    private final String apiKey;
    private final Bucket limiter;
    private final Duration maxRetryAfter;
    private final MeterRegistry meters;

    public TicketmasterClient(IngestionProperties properties, JsonMapper json, MeterRegistry meters) {
        IngestionProperties.Ticketmaster config = properties.ticketmaster();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requests.setReadTimeout(Duration.ofSeconds(20));
        this.rest = RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(requests)
                .build();
        this.json = json;
        this.apiKey = config.apiKey();
        this.limiter = Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(config.requestsPerSecond())
                        .refillGreedy(config.requestsPerSecond(), Duration.ofSeconds(1))
                        .build())
                .build();
        this.maxRetryAfter = config.maxRetryAfter();
        this.meters = meters;
    }

    /** The request as recorded in logs and {@code raw_payload.request}: path and sorted parameters, no key. */
    public static String describe(Map<String, String> query) {
        StringJoiner joined = new StringJoiner("&", EVENTS_PATH + "?", "");
        new TreeMap<>(query).forEach((name, value) -> joined.add(name + "=" + value));
        return joined.toString();
    }

    /**
     * One {@code events.json} page. Retried up to 4 times with exponential backoff and jitter on
     * {@link TransientSourceException}; a 429's {@code Retry-After} is honoured by sleeping here first.
     */
    @Retryable(interceptor = "sourceCalls")
    public JsonNode events(Map<String, String> query, CallBudget budget) {
        if (apiKey.isBlank()) {
            throw new SourceException("TICKETMASTER_API_KEY is not set (needed for INGESTION_MODE=live)");
        }
        String described = describe(query);
        try {
            limiter.asBlocking().consume(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("Interrupted while waiting for the Ticketmaster rate limit");
        }
        budget.record();
        try {
            return rest.get()
                    .uri(uri -> {
                        uri.path(EVENTS_PATH);
                        query.forEach(uri::queryParam);
                        return uri.queryParam("apikey", apiKey).build();
                    })
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 429) {
                            count("rate_limited");
                            sleepFor(response.getHeaders());
                            throw new TransientSourceException("Ticketmaster answered 429 for " + described);
                        }
                        if (status >= 500) {
                            count("server_error");
                            throw new TransientSourceException("Ticketmaster answered " + status + " for " + described);
                        }
                        if (status >= 400) {
                            count("client_error");
                            throw new SourceException("Ticketmaster answered " + status + " for " + described);
                        }
                        count("ok");
                        return json.readTree(response.getBody());
                    });
        } catch (ResourceAccessException e) {
            // Not chained as the cause: the original message quotes the request URL, key included.
            count("io_error");
            throw new TransientSourceException("I/O error calling Ticketmaster for " + described + " ("
                    + e.getMostSpecificCause().getClass().getSimpleName() + ")");
        }
    }

    /** Sleeps for the 429's {@code Retry-After} seconds (capped); the retry backoff is added on top. */
    private void sleepFor(HttpHeaders headers) {
        String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter == null) {
            return;
        }
        try {
            Duration wait = Duration.ofSeconds(Long.parseLong(retryAfter.strip()));
            Thread.sleep(wait.compareTo(maxRetryAfter) > 0 ? maxRetryAfter : wait);
        } catch (NumberFormatException e) {
            // An HTTP-date Retry-After: fall back to the normal backoff.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void count(String outcome) {
        meters.counter("soundcheck.ingestion.source.calls", "source", TicketmasterMapper.SOURCE, "outcome", outcome)
                .increment();
    }
}
