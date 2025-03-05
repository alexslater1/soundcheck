package app.soundcheck.ingestion.internal.enrich;

import app.soundcheck.ingestion.internal.IngestionProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Offline stand-in for Last.fm and MusicBrainz ({@code INGESTION_MODE=fixtures}): answers from
 * {@code fixtures/lastfm/top-tags.json} and {@code fixtures/musicbrainz/artist-search.json}, which hold
 * response bodies in each API's own shape keyed by lower-cased artist name. A missing file or name
 * behaves like "not found". The bodies go through the same parsing as the live clients.
 */
@Component
@ConditionalOnProperty(name = "soundcheck.ingestion.mode", havingValue = "fixtures", matchIfMissing = true)
public class FixtureEnrichment implements ArtistTags, MbidLookup {

    private final JsonNode topTags;
    private final JsonNode artistSearch;
    private final int minScore;

    public FixtureEnrichment(IngestionProperties properties, JsonMapper json) {
        this.topTags = read(json, Path.of(properties.fixturesDir(), "lastfm", "top-tags.json"));
        this.artistSearch = read(json, Path.of(properties.fixturesDir(), "musicbrainz", "artist-search.json"));
        this.minScore = properties.musicbrainz().minScore();
    }

    @Override
    public List<Tag> topTags(@Nullable UUID mbid, String name, PayloadSink sink) {
        JsonNode body = topTags.path(name.toLowerCase(Locale.ROOT));
        if (body.isMissingNode()) {
            return List.of();
        }
        sink.accept(LastfmClient.SOURCE, "fixture:top-tags.json#" + name, body);
        return LastfmClient.parse(body);
    }

    @Override
    public Optional<UUID> findMbid(String name, PayloadSink sink) {
        JsonNode body = artistSearch.path(name.toLowerCase(Locale.ROOT));
        if (body.isMissingNode()) {
            return Optional.empty();
        }
        sink.accept(MusicBrainzClient.SOURCE, "fixture:artist-search.json#" + name, body);
        return MusicBrainzClient.bestMatch(body, minScore);
    }

    private static JsonNode read(JsonMapper json, Path file) {
        if (!Files.exists(file)) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read fixture " + file, e);
        }
    }
}
