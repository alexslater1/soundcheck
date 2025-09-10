package app.soundcheck.ingestion.internal;

import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.shared.Ids;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

/**
 * A merge moves the duplicate's listings to the survivor, so the next ingestion run
 * maps the listing to the survivor (gig_source is keyed by source and external id) and recreates nothing.
 */
class MergedListingIT extends IntegrationTest {

    @Autowired
    IngestionService service;

    @Autowired
    DSLContext dsl;

    @Autowired
    TestRestTemplate rest;

    @Test
    void theNextRunMapsTheMovedListingToTheSurvivorAndRecreatesNothing() {
        service.run("TICKETMASTER", "TEST");
        var listing = dsl.select(GIG_SOURCE.GIG_ID, GIG_SOURCE.EXTERNAL_ID)
                .from(GIG_SOURCE)
                .where(GIG_SOURCE.SOURCE.eq("TICKETMASTER"))
                .and(GIG_SOURCE.PRIMARY_LISTING.isTrue())
                .orderBy(GIG_SOURCE.EXTERNAL_ID)
                .limit(1)
                .fetchSingle();
        UUID from = listing.value1();
        // The survivor is an exact copy (a fan added the same show), so the run that follows finds
        // nothing to change and other tests' fixture runs read the catalog as before.
        UUID into = copyOf(from);
        int listings = dsl.fetchCount(GIG_SOURCE, GIG_SOURCE.SOURCE.eq("TICKETMASTER"));
        TestApi api = new TestApi(rest);

        var merged = api.post("/api/v1/admin/catalog/gigs/" + from + "/merge", Map.of("into", into), api.admin());
        IngestionService.RunResult next = service.run("TICKETMASTER", "TEST");

        assertThat(merged.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(next.status()).isEqualTo("SUCCEEDED");
        assertThat(next.stats().get("created")).isEqualTo(0);
        assertThat(dsl.select(GIG_SOURCE.GIG_ID)
                        .from(GIG_SOURCE)
                        .where(GIG_SOURCE.SOURCE.eq("TICKETMASTER"))
                        .and(GIG_SOURCE.EXTERNAL_ID.eq(listing.value2()))
                        .fetchOne(GIG_SOURCE.GIG_ID))
                .isEqualTo(into);
        assertThat(dsl.fetchExists(GIG, GIG.ID.eq(from))).isFalse();
        assertThat(dsl.fetchCount(GIG_SOURCE, GIG_SOURCE.SOURCE.eq("TICKETMASTER")))
                .isEqualTo(listings);
    }

    /** A new gig with the same fields, content hash and bill as {@code gig}. */
    private UUID copyOf(UUID gig) {
        UUID copy = Ids.newId();
        dsl.execute(
                """
                insert into catalog.gig (id, title, venue_id, location, starts_at, doors_at, status, ticket_url,
                                         price_min, price_max, currency, image_url, content_hash)
                select ?, title, venue_id, location, starts_at, doors_at, status, ticket_url,
                       price_min, price_max, currency, image_url, content_hash
                  from catalog.gig where id = ?
                """,
                copy,
                gig);
        dsl.execute(
                """
                insert into catalog.gig_artist (gig_id, artist_id, billing_order)
                select ?, artist_id, billing_order from catalog.gig_artist where gig_id = ?
                """,
                copy,
                gig);
        return copy;
    }
}
