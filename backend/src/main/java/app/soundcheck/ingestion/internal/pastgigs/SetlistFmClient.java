package app.soundcheck.ingestion.internal.pastgigs;

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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * setlist.fm {@code GET /1.0/search/setlists}: past concerts of an artist in the UK, for the
 * "was it this gig?" step of adding a gig. The key travels in the {@code x-api-key} header and never in
 * a message or a log line. setlist.fm answers 404 when a search has no results (its documentation says
 * 200 with an empty list; both are read as "nothing"). 429 and 5xx are temporary.
 */
@Component
public class SetlistFmClient {

    static final String PATH = "/rest/1.0/search/setlists";
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final RestClient rest;
    private final JsonMapper json;
    private final String apiKey;
    private final Bucket limiter;
    private final MeterRegistry meters;

    public SetlistFmClient(IngestionProperties properties, JsonMapper json, MeterRegistry meters) {
        IngestionProperties.Setlistfm config = properties.setlistfm();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requests.setReadTimeout(Duration.ofSeconds(10));
        this.rest = RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(requests)
                .build();
        this.json = json;
        this.apiKey = config.apiKey();
        // Evenly spaced calls at the allowed rate, shared by every user of this instance.
        this.limiter = Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(1)
                        .refillIntervally(1, Duration.ofNanos(1_000_000_000L / config.requestsPerSecond()))
                        .build())
                .build();
        this.meters = meters;
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }

    /** UK setlists of an artist, newest first; {@code year} narrows to one year when given. */
    public PastGigPage search(String artistName, @Nullable Integer year, int page) {
        if (!configured()) {
            throw new SourceException("SETLISTFM_API_KEY is not set");
        }
        try {
            if (!limiter.asBlocking().tryConsume(1, Duration.ofSeconds(3))) {
                throw new TransientSourceException("setlist.fm rate limit: too many lookups at once");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("Interrupted while waiting for the setlist.fm rate limit");
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("artist", artistName);
        vars.put("page", page);
        vars.put("year", year == null ? "" : year);
        JsonNode body;
        try {
            body = rest.get()
                    .uri(uri -> {
                        // Values as URI variables: an artist may be called "Florence + The Machine".
                        uri.path(PATH)
                                .queryParam("artistName", "{artist}")
                                .queryParam("countryCode", "GB")
                                .queryParam("p", "{page}");
                        if (year != null) {
                            uri.queryParam("year", "{year}");
                        }
                        return uri.build(vars);
                    })
                    .header("x-api-key", apiKey)
                    .header("Accept", "application/json")
                    .header("Accept-Language", "en")
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) {
                            return null;
                        }
                        if (status == 429 || status >= 500) {
                            count("retryable");
                            throw new TransientSourceException("setlist.fm answered " + status);
                        }
                        if (status >= 400) {
                            count("client_error");
                            throw new SourceException("setlist.fm answered " + status);
                        }
                        try {
                            return json.readTree(response.getBody());
                        } catch (JacksonException e) {
                            count("unreadable");
                            throw new TransientSourceException("setlist.fm sent an unreadable body");
                        }
                    });
        } catch (ResourceAccessException e) {
            count("io_error");
            throw new TransientSourceException("I/O error calling setlist.fm ("
                    + e.getMostSpecificCause().getClass().getSimpleName() + ")");
        }
        if (body == null) {
            count("not_found");
            return new PastGigPage(List.of(), page, 0, 20);
        }
        count("ok");
        return parse(body);
    }

    /** The response's {@code setlist[]} as past gigs; entries without a date or venue are skipped. */
    static PastGigPage parse(JsonNode body) {
        List<PastGig> items = new ArrayList<>();
        for (JsonNode s : body.path("setlist")) {
            LocalDate date;
            try {
                date = LocalDate.parse(s.path("eventDate").asText(""), DATE);
            } catch (DateTimeParseException e) {
                continue;
            }
            JsonNode venue = s.path("venue");
            JsonNode city = venue.path("city");
            JsonNode coords = city.path("coords");
            String venueName = venue.path("name").asText("").strip();
            String artist = s.path("artist").path("name").asText("").strip();
            if (venueName.isEmpty() || artist.isEmpty()) {
                continue;
            }
            items.add(new PastGig(
                    s.path("id").asText(""),
                    s.path("url").asText(null),
                    date,
                    artist,
                    blankToNull(s.path("artist").path("mbid").asText(null)),
                    venueName,
                    blankToNull(city.path("name").asText(null)),
                    blankToNull(city.path("country").path("code").asText(null)),
                    coords.has("lat") ? coords.path("lat").asDouble() : null,
                    coords.has("long") ? coords.path("long").asDouble() : null,
                    blankToNull(s.path("tour").path("name").asText(null))));
        }
        return new PastGigPage(
                items,
                body.path("page").asInt(1),
                body.path("total").asInt(items.size()),
                body.path("itemsPerPage").asInt(20));
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void count(String outcome) {
        meters.counter("soundcheck.ingestion.source.calls", "source", "SETLISTFM", "outcome", outcome)
                .increment();
    }
}
