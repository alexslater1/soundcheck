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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Last.fm {@code artist.getTopTags}. Like the Ticketmaster client, the key is a query
 * parameter, so the client is built without request observations and never puts the URL in a message.
 * Last.fm reports problems as {@code {"error": n, "message": …}}: 6 = artist not found (an empty answer,
 * not a failure); 11, 16 and 29 are temporary and retried.
 */
@Component
@ConditionalOnProperty(name = "soundcheck.ingestion.mode", havingValue = "live")
public class LastfmClient implements ArtistTags {

    static final String PATH = "/2.0/";
    static final String SOURCE = "LASTFM";
    private static final int NOT_FOUND = 6;
    private static final Set<Integer> TEMPORARY = Set.of(11, 16, 29);

    private final RestClient rest;
    private final JsonMapper json;
    private final String apiKey;
    private final Bucket limiter;
    private final MeterRegistry meters;

    public LastfmClient(IngestionProperties properties, JsonMapper json, MeterRegistry meters) {
        IngestionProperties.Lastfm config = properties.lastfm();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requests.setReadTimeout(Duration.ofSeconds(15));
        this.rest = RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(requests)
                .build();
        this.json = json;
        this.apiKey = config.apiKey();
        // One token, refilled every 1/n second: calls are evenly spaced, with no burst at the start.
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
    public List<Tag> topTags(@Nullable UUID mbid, String name, PayloadSink sink) {
        if (apiKey.isBlank()) {
            throw new SourceException("LASTFM_API_KEY is not set (needed for INGESTION_MODE=live)");
        }
        String described = PATH + "?method=artist.gettoptags&" + (mbid != null ? "mbid=" + mbid : "artist=" + name);
        try {
            limiter.asBlocking().consume(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("Interrupted while waiting for the Last.fm rate limit");
        }
        JsonNode body;
        try {
            body = rest.get()
                    // Values go in as URI variables, never spliced into the template: a name may contain
                    // "+", "&" or "{", which must be percent-encoded ("Florence + The Machine").
                    .uri(uri -> {
                        uri.path(PATH).queryParam("method", "artist.gettoptags");
                        if (mbid != null) {
                            uri.queryParam("mbid", "{mbid}");
                        } else {
                            uri.queryParam("artist", "{artist}").queryParam("autocorrect", "1");
                        }
                        return uri.queryParam("format", "json")
                                .queryParam("api_key", "{key}")
                                .build(Map.of("mbid", String.valueOf(mbid), "artist", name, "key", apiKey));
                    })
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 429 || status >= 500) {
                            count("retryable");
                            throw new TransientSourceException("Last.fm answered " + status + " for " + described);
                        }
                        // Last.fm sends its error JSON with 4xx statuses too; read the body either way.
                        try {
                            return json.readTree(response.getBody());
                        } catch (JacksonException e) {
                            count("unreadable");
                            throw new TransientSourceException("Last.fm sent an unreadable body for " + described);
                        }
                    });
        } catch (ResourceAccessException e) {
            count("io_error");
            throw new TransientSourceException("I/O error calling Last.fm for " + described + " ("
                    + e.getMostSpecificCause().getClass().getSimpleName() + ")");
        }
        int error = body.path("error").asInt(0);
        if (error == NOT_FOUND) {
            count("not_found");
            return List.of();
        }
        if (TEMPORARY.contains(error)) {
            count("retryable");
            throw new TransientSourceException("Last.fm error " + error + " for " + described);
        }
        if (error != 0) {
            count("client_error");
            throw new SourceException("Last.fm error " + error + " for " + described);
        }
        count("ok");
        sink.accept(SOURCE, described, body);
        return parse(body);
    }

    /** {@code toptags.tag[]} → tags; shared with the fixture replay so both read the same shape. */
    static List<Tag> parse(JsonNode body) {
        List<Tag> tags = new ArrayList<>();
        for (JsonNode tag : body.path("toptags").path("tag")) {
            String name = tag.path("name").asText("");
            if (!name.isBlank()) {
                tags.add(new Tag(name, tag.path("count").asInt(0)));
            }
        }
        return tags;
    }

    private void count(String outcome) {
        meters.counter("soundcheck.ingestion.source.calls", "source", SOURCE, "outcome", outcome)
                .increment();
    }
}
