package app.soundcheck.ingestion.internal.enrich;

import app.soundcheck.catalog.GeoPoint;
import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.source.SourceException;
import app.soundcheck.ingestion.internal.source.TransientSourceException;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * postcodes.io bulk lookup: {@code POST /postcodes} with up to 100 postcodes, no key. Each answer
 * pairs the query with a result, or null when the postcode is unknown (mistyped or retired). Only the
 * coordinates are asked for ({@code filter}), and nothing else is kept.
 */
@Component
@ConditionalOnProperty(name = "soundcheck.ingestion.mode", havingValue = "live")
public class PostcodesIoClient implements PostcodeLookup {

    static final String PATH = "/postcodes";
    static final String SOURCE = "POSTCODES_IO";

    private final RestClient rest;
    private final JsonMapper json;
    private final Bucket limiter;
    private final MeterRegistry meters;

    public PostcodesIoClient(IngestionProperties properties, JsonMapper json, MeterRegistry meters) {
        IngestionProperties.Geocoding config = properties.geocoding();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                // HTTP/1.1: the JDK client's default h2c upgrade on a plain-HTTP POST with a body fails
                // (seen as an IOException against the test server); postcodes.io needs nothing newer.
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build());
        requests.setReadTimeout(Duration.ofSeconds(15));
        this.rest = RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(requests)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.venues().userAgent())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
        this.json = json;
        this.limiter = Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(1)
                        .refillIntervally(1, Duration.ofNanos(1_000_000_000L / config.requestsPerSecond()))
                        .build())
                .build();
        this.meters = meters;
    }

    @Override
    @Retryable(interceptor = "sourceCalls")
    public Map<String, GeoPoint> locate(List<String> postcodes) {
        if (postcodes.isEmpty()) {
            return Map.of();
        }
        if (postcodes.size() > BATCH) {
            throw new IllegalArgumentException("postcodes.io takes at most " + BATCH + " postcodes a call");
        }
        try {
            limiter.asBlocking().consume(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("Interrupted while waiting for the postcodes.io rate limit");
        }
        String described = PATH + " (" + postcodes.size() + " postcodes)";
        JsonNode body;
        try {
            body = rest.post()
                    .uri(uri -> uri.path(PATH)
                            .queryParam("filter", "postcode,latitude,longitude")
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("postcodes", postcodes))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 429 || status >= 500) {
                            count("retryable");
                            throw new TransientSourceException("postcodes.io answered " + status + " for " + described);
                        }
                        if (status >= 400) {
                            count("client_error");
                            throw new SourceException("postcodes.io answered " + status + " for " + described);
                        }
                        try {
                            return json.readTree(response.getBody());
                        } catch (JacksonException e) {
                            count("unreadable");
                            throw new TransientSourceException("postcodes.io sent an unreadable body for " + described);
                        }
                    });
        } catch (ResourceAccessException e) {
            count("io_error");
            throw new TransientSourceException("I/O error calling postcodes.io for " + described + " ("
                    + e.getMostSpecificCause().getClass().getSimpleName() + ")");
        }
        count("ok");
        return points(body);
    }

    /** {@code {"result": [{"query": "SG14 1AL", "result": {"latitude": …, "longitude": …}} | {…, "result": null}]}}. */
    static Map<String, GeoPoint> points(JsonNode body) {
        Map<String, GeoPoint> found = new LinkedHashMap<>();
        for (JsonNode answer : body.path("result")) {
            JsonNode place = answer.path("result");
            if (place.path("latitude").isNumber() && place.path("longitude").isNumber()) {
                found.put(
                        answer.path("query").asText(""),
                        new GeoPoint(
                                place.path("latitude").asDouble(),
                                place.path("longitude").asDouble()));
            }
        }
        return found;
    }

    private void count(String outcome) {
        meters.counter("soundcheck.ingestion.source.calls", "source", SOURCE, "outcome", outcome)
                .increment();
    }
}
