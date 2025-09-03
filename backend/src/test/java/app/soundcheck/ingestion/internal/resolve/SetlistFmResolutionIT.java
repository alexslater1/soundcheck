package app.soundcheck.ingestion.internal.resolve;

import static app.soundcheck.shared.jooq.catalog.Tables.ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.ingestion.internal.RunStats;
import app.soundcheck.ingestion.internal.resolve.EntityResolver.Outcome;
import app.soundcheck.ingestion.internal.source.SourceArtist;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceVenue;
import app.soundcheck.ingestion.internal.source.TimePrecision;
import app.soundcheck.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Past gigs against real PostgreSQL: setlist.fm listings (date-only, venue at its town's centre, one act
 * each) join the gigs the catalog already has, and two acts' setlists of one night make one gig.
 */
class SetlistFmResolutionIT extends IntegrationTest {

    static final Instant SEEN = Instant.parse("2026-10-04T05:10:00Z");
    /** Manchester's centre, as setlist.fm gives a Manchester venue's coordinates. */
    static final double TOWN_LAT = 53.4808, TOWN_LNG = -2.2426;

    @Autowired
    EntityResolver resolver;

    @Autowired
    DSLContext dsl;

    @Test
    void aSetlistJoinsTheTicketmasterGigAtTheRealVenueAndAddsItsMissingAct() {
        SourceGig ticketed = new SourceGig(
                "TICKETMASTER",
                "tm-slfm-1",
                "https://tickets.example.com/tm-slfm-1",
                "Quiet Meridian",
                Instant.parse("2025-03-14T20:00:00Z"),
                null,
                "SCHEDULED",
                null,
                null,
                null,
                null,
                null,
                // The venue's own coordinates: about 1 km from the town centre setlist.fm gives.
                new SourceVenue(
                        "tm-v-slfm-1",
                        "The Copper Room",
                        null,
                        "Manchester",
                        "M4 1AA",
                        53.4870,
                        -2.2300,
                        "Europe/London"),
                List.of(new SourceArtist("tm-a-slfm-1", "Quiet Meridian", null, List.of(), null)));
        assertThat(resolver.ingest(ticketed, new RunStats(), SEEN)).isEqualTo(Outcome.CREATED);
        UUID gig = gigOf("TICKETMASTER", "tm-slfm-1");

        // The support's setlist: the same venue by name, placed at the town's centre, date only.
        RunStats stats = new RunStats();
        assertThat(resolver.ingest(
                        setlist("slfm-1a", "v-copper", "the copper room", "2025-03-14", "Velvet Arcade"), stats, SEEN))
                .isEqualTo(Outcome.MERGED);
        assertThat(stats.venues.matchedFuzzy).isEqualTo(1); // 30 km and a near-identical name
        assertThat(stats.venues.created).isZero();
        assertThat(gigOf("SETLISTFM", "slfm-1a")).isEqualTo(gig);
        assertThat(bill(gig)).containsExactly("Quiet Meridian", "Velvet Arcade");
        // Ticketmaster's listing still supplies the gig's fields; the setlist's is the link back.
        assertThat(dsl.select(GIG_SOURCE.PRIMARY_LISTING, GIG_SOURCE.URL)
                        .from(GIG_SOURCE)
                        .where(GIG_SOURCE.SOURCE.eq("SETLISTFM").and(GIG_SOURCE.EXTERNAL_ID.eq("slfm-1a")))
                        .fetchSingle()
                        .into(Object[].class))
                .containsExactly(false, "https://www.setlist.fm/setlist/x/slfm-1a.html");
        assertThat(dsl.select(GIG.STARTS_AT)
                        .from(GIG)
                        .where(GIG.ID.eq(gig))
                        .fetchSingle(GIG.STARTS_AT)
                        .toInstant())
                .isEqualTo(Instant.parse("2025-03-14T20:00:00Z"));

        // A second run sees the same setlist: nothing changes.
        RunStats again = new RunStats();
        assertThat(resolver.ingest(
                        setlist("slfm-1a", "v-copper", "the copper room", "2025-03-14", "Velvet Arcade"), again, SEEN))
                .isEqualTo(Outcome.UNCHANGED);
        assertThat(again.venues.matchedByExternalId).isEqualTo(1);
    }

    @Test
    void twoActsSetlistsOfOneNightAtAVenueTheCatalogLacksMakeOneGig() {
        RunStats stats = new RunStats();
        assertThat(resolver.ingest(
                        setlist("slfm-2a", "v-loft", "Saltmarsh Loft", "2025-05-02", "Ember Tide"), stats, SEEN))
                .isEqualTo(Outcome.CREATED);
        assertThat(resolver.ingest(
                        setlist("slfm-2b", "v-loft", "Saltmarsh Loft", "2025-05-02", "Glass Orchard"), stats, SEEN))
                .isEqualTo(Outcome.MERGED);
        assertThat(stats.venues.created).isEqualTo(1);

        UUID gig = gigOf("SETLISTFM", "slfm-2a");
        assertThat(gigOf("SETLISTFM", "slfm-2b")).isEqualTo(gig);
        assertThat(bill(gig)).containsExactly("Ember Tide", "Glass Orchard");

        // The next night is another gig.
        assertThat(resolver.ingest(
                        setlist("slfm-2c", "v-loft", "Saltmarsh Loft", "2025-05-03", "Ember Tide"),
                        new RunStats(),
                        SEEN))
                .isEqualTo(Outcome.CREATED);
    }

    @Test
    void aSetlistMatchingNoActOfTwoGigsThatNightStaysApartAndIsLoggedForReview() {
        resolver.ingest(
                setlist("slfm-4a", "v-dock", "Dockside Vault", "2025-07-11", "Lark Engine"), new RunStats(), SEEN);
        // A second gig that night in the same room (an early show from a fan, say): another act, another source.
        SourceGig fan = new SourceGig(
                "USER",
                "fan-slfm-4",
                null,
                "Mirror Fields",
                Instant.parse("2025-07-11T13:00:00Z"),
                null,
                "SCHEDULED",
                null,
                null,
                null,
                null,
                null,
                new SourceVenue(
                        "fan-v-4", "Dockside Vault", null, "Manchester", null, TOWN_LAT, TOWN_LNG, "Europe/London"),
                List.of(new SourceArtist("", "Mirror Fields", null, List.of(), null)),
                TimePrecision.DATE_ONLY);
        resolver.ingest(fan, new RunStats(), SEEN);

        RunStats stats = new RunStats();
        assertThat(resolver.ingest(
                        setlist("slfm-4b", "v-dock", "Dockside Vault", "2025-07-11", "Coal Sparrows"), stats, SEEN))
                .isEqualTo(Outcome.CREATED);
        assertThat(stats.flaggedForReview).isEqualTo(2);
    }

    @Test
    void aDifferentlyNamedVenueInTheSameTownIsNotJoined() {
        resolver.ingest(
                setlist("slfm-3a", "v-hall", "Northern Lamp Hall", "2025-06-06", "Paper Comets"), new RunStats(), SEEN);

        RunStats stats = new RunStats();
        resolver.ingest(setlist("slfm-3b", "v-lamp", "Northern Lamp", "2025-06-06", "Paper Comets"), stats, SEEN);

        assertThat(stats.venues.created).isEqualTo(1);
        assertThat(gigOf("SETLISTFM", "slfm-3b")).isNotEqualTo(gigOf("SETLISTFM", "slfm-3a"));
    }

    private static SourceGig setlist(String id, String venueId, String venueName, String day, String act) {
        return new SourceGig(
                "SETLISTFM",
                id,
                "https://www.setlist.fm/setlist/x/" + id + ".html",
                act,
                java.time.LocalDate.parse(day)
                        .atTime(20, 0)
                        .atZone(java.time.ZoneId.of("Europe/London"))
                        .toInstant(),
                null,
                "SCHEDULED",
                null,
                null,
                null,
                null,
                null,
                new SourceVenue(venueId, venueName, null, "Manchester", null, TOWN_LAT, TOWN_LNG, "Europe/London"),
                List.of(new SourceArtist("", act, null, List.of(), null)),
                TimePrecision.DATE_ONLY);
    }

    private UUID gigOf(String source, String externalId) {
        return dsl.select(GIG_SOURCE.GIG_ID)
                .from(GIG_SOURCE)
                .where(GIG_SOURCE.SOURCE.eq(source))
                .and(GIG_SOURCE.EXTERNAL_ID.eq(externalId))
                .fetchSingle(GIG_SOURCE.GIG_ID);
    }

    private List<String> bill(UUID gigId) {
        return dsl.select(ARTIST.NAME)
                .from(GIG_ARTIST)
                .join(ARTIST)
                .on(ARTIST.ID.eq(GIG_ARTIST.ARTIST_ID))
                .where(GIG_ARTIST.GIG_ID.eq(gigId))
                .orderBy(GIG_ARTIST.BILLING_ORDER)
                .fetch(ARTIST.NAME);
    }
}
