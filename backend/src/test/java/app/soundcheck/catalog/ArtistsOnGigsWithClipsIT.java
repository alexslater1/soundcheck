package app.soundcheck.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.catalog.CatalogIngest.BilledArtist;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The acts whose past gigs the setlist.fm backfill reads are those on the bill of a gig with a
 * published clip. Posts belong to content; the catalog asks through {@link GigClips}.
 */
class ArtistsOnGigsWithClipsIT extends IntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    CatalogIngest catalogIngest;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ApplicationEventPublisher events;

    @Test
    void everyActOnTheBillOfAGigWithAClipAndNoOther() {
        TestApi api = new TestApi(rest);
        TestData data = new TestData(catalogIngest, dsl, tx, events);
        TestApi.User fan = api.register("filmer");
        UUID headliner = data.artist("Clip Headliner " + UUID.randomUUID());
        UUID support = data.artist("Clip Support " + UUID.randomUUID());
        UUID unfilmed = data.artist("Never Filmed " + UUID.randomUUID());
        UUID filmedGig = data.gig(
                "Filmed night",
                data.venue("Filmed Room " + UUID.randomUUID()),
                Instant.parse("2026-08-01T19:30:00Z"),
                headliner,
                support);
        data.gig(
                "Quiet night",
                data.venue("Quiet Room " + UUID.randomUUID()),
                Instant.parse("2026-08-02T19:30:00Z"),
                unfilmed);
        api.post(
                "/api/v1/posts",
                Map.of(
                        "clips",
                        List.of(Map.of("mediaId", data.video(fan.id(), "READY"))),
                        "gigId",
                        filmedGig,
                        "artistIds",
                        List.of()),
                fan);

        List<UUID> acts = catalogIngest.artistsOnGigsWithClips().stream()
                .map(BilledArtist::id)
                .toList();

        assertThat(acts).contains(headliner, support).doesNotContain(unfilmed);
    }
}
