package app.soundcheck.ingestion.internal.enrich;

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
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * MusicBrainz artist search: no key, but at most one request per second and a descriptive
 * {@code User-Agent} are required; exceeding the rate gets a 503, which is retried.
 */
@Component
@ConditionalOnProperty(name = "soundcheck.ingestion.mode", havingValue = "live")
public class MusicBrainzClient implements MbidLookup {

    static final String PATH = "/ws/2/artist";
    static final String SOURCE = "MUSICBRAINZ";

    private final RestClient rest;
    private final JsonMapper json;
    private final Bucket limiter;
    private final int minScore;
    private final MeterRegistry meters;

    public MusicBrainzClient(IngestionProperties properties, JsonMapper json, MeterRegistry meters) {
        IngestionProperties.MusicBrainz config = properties.musicbrainz();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requests.setReadTimeout(Duration.ofSeconds(15));
        this.rest = RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(requests)
                .defaultHeader(HttpHeaders.USER_AGENT, config.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
        this.json = json;
        // One token, refilled every 1/n second: calls are evenly spaced, with no burst at the start.
        this.limiter = Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(1)
                        .refillIntervally(1, Duration.ofNanos(1_000_000_000L / config.requestsPerSecond()))
                        .build())
                .build();
        this.minScore = config.minScore();
        this.meters = meters;
    }

    @Override
    @Retryable(interceptor = "sourceCalls")
    public Optional<UUID> findMbid(String name, PayloadSink sink) {
        // Lucene query: an exact phrase on the artist field; quotes and backslashes in the name are escaped.
        String query = "artist:\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        String described = PATH + "?query=" + query + "&limit=3";
        try {
            limiter.asBlocking().consume(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("Interrupted while waiting for the MusicBrainz rate limit");
        }
        JsonNode body;
        try {
            body = rest.get()
                    .uri(uri -> uri.path(PATH)
                            .queryParam("query", "{query}")
                            .queryParam("fmt", "json")
                            .queryParam("limit", "3")
                            .build(query))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 429 || status >= 500) {
                            count("retryable");
                            throw new TransientSourceException("MusicBrainz answered " + status + " for " + described);
                        }
                        if (status >= 400) {
                            count("client_error");
                            throw new SourceException("MusicBrainz answered " + status + " for " + described);
                        }
                        try {
                            return json.readTree(response.getBody());
                        } catch (JacksonException e) {
                            count("unreadable");
                            throw new TransientSourceException("MusicBrainz sent an unreadable body for " + described);
                        }
                    });
        } catch (ResourceAccessException e) {
            count("io_error");
            throw new TransientSourceException("I/O error calling MusicBrainz for " + described + " ("
                    + e.getMostSpecificCause().getClass().getSimpleName() + ")");
        }
        count("ok");
        sink.accept(SOURCE, described, body);
        return bestMatch(body, minScore);
    }

    /** The first hit (MusicBrainz orders by score) if it reaches {@code minScore} and has a valid id. */
    static Optional<UUID> bestMatch(JsonNode body, int minScore) {
        JsonNode best = body.path("artists").path(0);
        if (best.isMissingNode() || best.path("score").asInt(0) < minScore) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(best.path("id").asText("")));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private void count(String outcome) {
        meters.counter("soundcheck.ingestion.source.calls", "source", SOURCE, "outcome", outcome)
                .increment();
    }
}
