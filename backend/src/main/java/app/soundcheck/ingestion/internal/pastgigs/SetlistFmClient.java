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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * setlist.fm {@code GET /1.0/search/setlists} and {@code GET /1.0/setlist/{id}}: past concerts of an
 * artist in the UK, for the "was it this gig?" step of adding a gig, and the songs played at a
 * past gig, for its page; an act's past gigs page by page, for the backfill. The key travels in the {@code x-api-key} header and never in
 * a message or a log line. setlist.fm answers 404 when a search has no results (its documentation says
 * 200 with an empty list; both are read as "nothing"). 429 and 5xx are temporary.
 */
@Component
public class SetlistFmClient {

    static final String PATH = "/rest/1.0/search/setlists";
    static final String SETLIST_PATH = "/rest/1.0/setlist";
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
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("artistName", artistName);
        query.put("countryCode", "GB");
        query.put("p", page);
        if (year != null) {
            query.put("year", year);
        }
        JsonNode body = fetch(PATH, query);
        return body == null ? new PastGigPage(List.of(), page, 0, 20) : parse(body);
    }

    /**
     * The UK setlists of one act on one day: by MusicBrainz id when the catalog knows it
     * (exact), else by name. Usually one; a festival day or a mistyped entry can give more.
     */
    public List<Setlist> setlistsOn(@Nullable UUID artistMbid, String artistName, LocalDate date) {
        Map<String, Object> query = new LinkedHashMap<>();
        if (artistMbid != null) {
            query.put("artistMbid", artistMbid.toString());
        } else {
            query.put("artistName", artistName);
        }
        query.put("countryCode", "GB");
        query.put("date", DATE.format(date));
        JsonNode body = fetch(PATH, query);
        if (body == null) {
            return List.of();
        }
        List<Setlist> setlists = new ArrayList<>();
        for (JsonNode s : body.path("setlist")) {
            parseSetlist(s).ifPresent(setlists::add);
        }
        return setlists;
    }

    /**
     * One page of an act's UK setlists, newest first, as setlist.fm sends it (the backfill): by
     * MusicBrainz id when known (exact), else by name. Null when setlist.fm has none.
     */
    public @Nullable JsonNode artistSetlists(@Nullable UUID artistMbid, String artistName, int page) {
        Map<String, Object> query = new LinkedHashMap<>();
        if (artistMbid != null) {
            query.put("artistMbid", artistMbid.toString());
        } else {
            query.put("artistName", artistName);
        }
        query.put("countryCode", "GB");
        query.put("p", page);
        return fetch(PATH, query);
    }

    /** One setlist by its setlist.fm id (a gig added from setlist.fm keeps it). */
    public Optional<Setlist> setlist(String setlistId) {
        JsonNode body = fetch(SETLIST_PATH + "/" + URLEncoder.encode(setlistId, StandardCharsets.UTF_8), Map.of());
        return body == null ? Optional.empty() : parseSetlist(body);
    }

    /**
     * One GET against setlist.fm at the shared rate, with the key in its header; null when setlist.fm
     * has nothing (404). Values travel as URI variables: an artist may be called "Florence + The Machine".
     */
    private @Nullable JsonNode fetch(String path, Map<String, Object> query) {
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
        JsonNode body;
        try {
            body = rest.get()
                    .uri(uri -> {
                        uri.path(path);
                        query.keySet().forEach(name -> uri.queryParam(name, "{" + name + "}"));
                        return uri.build(query);
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
        count(body == null ? "not_found" : "ok");
        return body;
    }

    /**
     * A setlist's songs as setlist.fm structures them: {@code sets.set[]}, each with an optional name or
     * an {@code encore} number and its {@code song[]}. Empty when it has no songs (a placeholder someone
     * created before anyone filled it in).
     */
    static Optional<Setlist> parseSetlist(JsonNode s) {
        List<Setlist.SetlistPart> parts = new ArrayList<>();
        for (JsonNode set : s.path("sets").path("set")) {
            List<Setlist.SetlistSong> songs = new ArrayList<>();
            for (JsonNode song : set.path("song")) {
                String name = song.path("name").asText("").strip();
                if (name.isEmpty()) {
                    continue;
                }
                songs.add(new Setlist.SetlistSong(
                        name,
                        blankToNull(song.path("info").asText(null)),
                        blankToNull(song.path("cover").path("name").asText(null)),
                        song.path("tape").asBoolean(false)));
            }
            if (songs.isEmpty()) {
                continue;
            }
            int encore = set.path("encore").asInt(0);
            String title = blankToNull(set.path("name").asText(null));
            if (title == null && encore > 0) {
                title = encore == 1 ? "Encore" : "Encore " + encore;
            }
            parts.add(new Setlist.SetlistPart(title, songs));
        }
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        LocalDate date;
        try {
            date = LocalDate.parse(s.path("eventDate").asText(""), DATE);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        return Optional.of(new Setlist(
                s.path("id").asText(""),
                s.path("url").asText(null),
                s.path("artist").path("name").asText("").strip(),
                s.path("venue").path("name").asText("").strip(),
                blankToNull(s.path("venue").path("city").path("name").asText(null)),
                date,
                blankToNull(s.path("tour").path("name").asText(null)),
                parts));
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
