package app.soundcheck.ingestion.internal.enrich;

import static app.soundcheck.shared.jooq.catalog.Tables.ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.ARTIST_EXTERNAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.ingestion.internal.RunStats;
import app.soundcheck.ingestion.internal.resolve.EntityResolver;
import app.soundcheck.ingestion.internal.source.CallBudget;
import app.soundcheck.ingestion.internal.source.GigSource;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Genre coverage on the fixture set (fixtures mode: Last.fm and MusicBrainz answers come from
 * {@code fixtures/}): after ingestion + enrichment at least 80 % of the fixture's artists have a genre.
 */
class ArtistEnricherIT extends IntegrationTest {

    @Autowired
    ArtistEnricher enricher;

    @Autowired
    EntityResolver resolver;

    @Autowired
    GigSource fixtures;

    @Autowired
    CatalogIngest catalog;

    @Autowired
    DSLContext dsl;

    @Autowired
    MutableClock clock;

    @AfterEach
    void backToRealTime() {
        clock.reset();
    }

    @Test
    void fixtureArtistsGetMbidsAndGenresAndAreNotEnrichedTwice() {
        RunStats ingest = new RunStats();
        fixtures.pages(Instant.EPOCH, Instant.EPOCH, new CallBudget(0))
                .flatMap(p -> p.gigs().stream())
                .forEach(g -> resolver.ingest(g, ingest, Instant.now(clock)));

        // Another test class may already have run a full ingestion (which enriches) in the shared
        // database. Put the fixture's artists back to "never enriched", and clear the MusicBrainz ids
        // that did not come from the ticketing source, so this test observes a first enrichment.
        List<String> withoutSourceMbid = fixtures.pages(Instant.EPOCH, Instant.EPOCH, new CallBudget(0))
                .flatMap(p -> p.gigs().stream())
                .flatMap(g -> g.artists().stream())
                .filter(a -> a.mbid() == null)
                .map(a -> a.externalId())
                .distinct()
                .toList();
        dsl.update(ARTIST)
                .setNull(ARTIST.MBID)
                .where(ARTIST.ID.in(dsl.select(ARTIST_EXTERNAL_ID.ARTIST_ID)
                        .from(ARTIST_EXTERNAL_ID)
                        .where(ARTIST_EXTERNAL_ID.EXTERNAL_ID.in(withoutSourceMbid))))
                .execute();
        dsl.update(ARTIST)
                .setNull(ARTIST.ENRICHED_AT)
                .where(ARTIST.ID.in(dsl.select(ARTIST_EXTERNAL_ID.ARTIST_ID)
                        .from(ARTIST_EXTERNAL_ID)
                        .where(ARTIST_EXTERNAL_ID.EXTERNAL_ID.like("K8vZsyn%"))))
                .execute();

        List<String> payloads = new ArrayList<>();
        PayloadSink sink = (source, request, body) -> payloads.add(source);
        ArtistEnricher.Result total = new ArtistEnricher.Result();
        RunStats stats = new RunStats();
        // The per-run cap may leave some for a later pass; loop until nothing is pending.
        for (ArtistEnricher.Result pass = enricher.enrichPending(stats, sink);
                pass.artists > 0;
                pass = enricher.enrichPending(stats, sink)) {
            total.artists += pass.artists;
            total.mbidsFound += pass.mbidsFound;
            total.genresFromTags += pass.genresFromTags;
            total.keptFallbackGenres += pass.keptFallbackGenres;
            total.withoutGenres += pass.withoutGenres;
            total.errors += pass.errors;
        }

        List<UUID> fixtureArtists = dsl.selectDistinct(ARTIST_EXTERNAL_ID.ARTIST_ID)
                .from(ARTIST_EXTERNAL_ID)
                .where(ARTIST_EXTERNAL_ID.EXTERNAL_ID.like("K8vZsyn%"))
                .fetch(ARTIST_EXTERNAL_ID.ARTIST_ID);
        assertThat(fixtureArtists).hasSizeGreaterThan(100);

        // The target: ≥ 1 genre for ≥ 80 % of the fixture's artists.
        double withGenres = catalog.countWithGenres(fixtureArtists) / (double) fixtureArtists.size();
        assertThat(withGenres).isGreaterThanOrEqualTo(0.80);

        assertThat(total.errors).isZero();
        assertThat(total.artists).isGreaterThanOrEqualTo(fixtureArtists.size());
        assertThat(total.mbidsFound).isPositive(); // MusicBrainz search filled in missing ids…
        assertThat(total.genresFromTags).isPositive(); // …Last.fm tags became genres…
        assertThat(total.keptFallbackGenres).isPositive(); // …and some acts kept their source classification
        assertThat(total.withoutGenres).isPositive();
        assertThat(stats.issueCounts()).containsKey("ARTIST_NO_GENRES");
        assertThat(payloads).contains("LASTFM", "MUSICBRAINZ");

        // Everyone now has enriched_at; a second pass has nothing to do…
        assertThat(dsl.fetchCount(ARTIST, ARTIST.ID.in(fixtureArtists).and(ARTIST.ENRICHED_AT.isNull())))
                .isZero();
        assertThat(enricher.enrichPending(new RunStats(), sink).artists).isZero();
        // …until the data is more than 30 days old.
        clock.advance(Duration.ofDays(31));
        assertThat(enricher.enrichPending(new RunStats(), sink).artists).isPositive();
    }

    @Test
    void tagsBecomeTheTopThreeGenresWeightedByCountAndAliasWeight() {
        // indie rock → indie (alias weight 1.0); britpop → indie (0.9); house → house; techno → techno;
        // jazz → jazz; "seen live" maps to nothing; "shoegaze" is below the count threshold of 10.
        Map<Short, Float> genres = enricher.genresFromTags(List.of(
                new ArtistTags.Tag("indie rock", 100),
                new ArtistTags.Tag("seen live", 90),
                new ArtistTags.Tag("Britpop", 40),
                new ArtistTags.Tag("house", 30),
                new ArtistTags.Tag("techno", 20),
                new ArtistTags.Tag("jazz", 12),
                new ArtistTags.Tag("shoegaze", 9)));

        // indie = 100 × 1.0 + 40 × 0.9 = 136, house = 30, techno = 20 (jazz at 12 is fourth and dropped).
        assertThat(genres.keySet()).containsExactly((short) 2, (short) 13, (short) 14);
        assertThat(genres.get((short) 2)).isCloseTo(136f / 186f, within(1e-5f));
        assertThat(genres.get((short) 13)).isCloseTo(30f / 186f, within(1e-5f));
        assertThat(genres.values().stream().mapToDouble(Float::doubleValue).sum())
                .isCloseTo(1.0, within(1e-5));

        assertThat(enricher.genresFromTags(List.of(new ArtistTags.Tag("seen live", 100))))
                .isEmpty();
    }
}
