package app.soundcheck.ingestion.internal.enrich;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.catalog.CatalogIngest.ArtistToEnrich;
import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.RunStats;
import app.soundcheck.ingestion.internal.resolve.GenreWeights;
import app.soundcheck.ingestion.internal.source.SourceException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Enrichment for artists that are new or were last enriched more than 30 days ago:
 *
 * <ol>
 *   <li>no MusicBrainz id yet → search MusicBrainz by name, accept a hit scoring ≥ 95;
 *   <li>Last.fm top tags (by MBID when known, else by name) → keep tags with count ≥ 10 that map through
 *       {@code genre_alias} → weight = count × alias weight → top 3, normalised to sum 1 → {@code artist_genre};
 *   <li>no usable tags → the genres stored at creation (from the ticketing source's own classification)
 *       stay as the fallback.
 * </ol>
 *
 * One artist failing (a source error after retries) is counted and skipped; it is retried next run
 * because {@code enriched_at} is only set on success.
 */
@Service
public class ArtistEnricher {

    private static final Logger log = LoggerFactory.getLogger(ArtistEnricher.class);

    private final CatalogIngest catalog;
    private final ArtistTags tags;
    private final MbidLookup mbids;
    private final IngestionProperties.Enrichment settings;
    private final Clock clock;

    public ArtistEnricher(
            CatalogIngest catalog, ArtistTags tags, MbidLookup mbids, IngestionProperties properties, Clock clock) {
        this.catalog = catalog;
        this.tags = tags;
        this.mbids = mbids;
        this.settings = properties.enrichment();
        this.clock = clock;
    }

    /** What an enrichment pass did; stored under {@code enrichment} in the run's stats. */
    public static final class Result {
        public int artists;
        public int mbidsFound;
        public int genresFromTags;
        public int keptFallbackGenres;
        public int withoutGenres;
        public int errors;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("artists", artists);
            map.put("mbidsFound", mbidsFound);
            map.put("genresFromTags", genresFromTags);
            map.put("keptFallbackGenres", keptFallbackGenres);
            map.put("withoutGenres", withoutGenres);
            map.put("errors", errors);
            return map;
        }
    }

    public Result enrichPending(RunStats stats, PayloadSink sink) {
        Result result = new Result();
        Instant now = Instant.now(clock);
        List<ArtistToEnrich> pending =
                catalog.artistsToEnrich(now.minus(settings.staleAfter()), settings.maxArtistsPerRun());
        for (ArtistToEnrich artist : pending) {
            result.artists++;
            try {
                enrich(artist, result, stats, sink, now);
            } catch (SourceException e) {
                result.errors++;
                stats.issue("ENRICHMENT_FAILED", artist.id().toString(), e.getMessage());
                log.warn("Enrichment failed for artist {}: {}", artist.id(), e.getMessage());
            }
        }
        return result;
    }

    private void enrich(ArtistToEnrich artist, Result result, RunStats stats, PayloadSink sink, Instant now) {
        UUID mbid = artist.mbid();
        if (mbid == null) {
            Optional<UUID> found = mbids.findMbid(artist.name(), sink);
            if (found.isPresent()) {
                mbid = found.get();
                result.mbidsFound++;
                catalog.completeArtist(artist.id(), mbid, null);
            }
        }
        Map<Short, Float> genres = genresFromTags(tags.topTags(mbid, artist.name(), sink));
        if (!genres.isEmpty()) {
            catalog.replaceArtistGenres(artist.id(), genres);
            result.genresFromTags++;
        } else if (catalog.countWithGenres(List.of(artist.id())) > 0) {
            result.keptFallbackGenres++;
        } else {
            result.withoutGenres++;
            stats.issue("ARTIST_NO_GENRES", artist.id().toString(), artist.name());
        }
        catalog.markArtistEnriched(artist.id(), now);
    }

    /** Tags → genre weights: count ≥ 10, mapped through the alias table, top 3, normalised to sum 1. */
    Map<Short, Float> genresFromTags(List<ArtistTags.Tag> topTags) {
        List<ArtistTags.Tag> used = topTags.stream()
                .filter(tag -> tag.count() >= settings.minTagCount())
                .toList();
        Map<String, CatalogIngest.GenreMatch> matches =
                catalog.genresForAliases(used.stream().map(ArtistTags.Tag::name).toList());
        Map<Short, Float> raw = new LinkedHashMap<>();
        for (ArtistTags.Tag tag : used) {
            CatalogIngest.GenreMatch match = matches.get(tag.name().toLowerCase(Locale.ROOT));
            if (match != null) {
                raw.merge(match.genreId(), tag.count() * match.weight(), Float::sum);
            }
        }
        return GenreWeights.topNormalised(raw, 3);
    }
}
