package app.soundcheck.ingestion.internal.resolve;

import static app.soundcheck.shared.jooq.catalog.Tables.ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_SOURCE;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE_EXTERNAL_ID;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE_SIBLING;
import static app.soundcheck.shared.jooq.ingestion.Tables.MATCH_REVIEW;
import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.catalog.VenueDraft;
import app.soundcheck.ingestion.internal.RunStats;
import app.soundcheck.ingestion.internal.resolve.EntityResolver.Outcome;
import app.soundcheck.ingestion.internal.source.SourceArtist;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceVenue;
import app.soundcheck.ingestion.internal.source.TimePrecision;
import app.soundcheck.support.IntegrationTest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Venue listing matching against real PostgreSQL: a venue's own site (names only, doors times, acts packed into
 * the title) meets Ticketmaster and fan-added listings of the same nights. Each test uses its own venue
 * and act names (a random tag), far from the fixtures.
 */
class VenueListingMatchingIT extends IntegrationTest {

    static final Instant SEEN = Instant.parse("2026-10-02T05:10:00Z");

    @Autowired
    EntityResolver resolver;

    @Autowired
    DSLContext dsl;

    @Autowired
    CatalogIngest catalog;

    String tag;
    SourceVenue tmVenue;
    SourceVenue siteVenue;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 6);
        double lat = 59.0 + Math.random(); // a spot of its own, so venue matching sees only this test's venue
        tmVenue = new SourceVenue("tmv-" + tag, "The Lantern " + tag, null, "London", null, lat, -0.5, "Europe/London");
        // The venue's own adapter: its own key, the same place, its own spelling.
        siteVenue =
                new SourceVenue("lantern-" + tag, "Lantern " + tag, null, "London", null, lat, -0.5, "Europe/London");
    }

    @Test
    void theVenueSitesListingJoinsTicketmastersGigAndAddsTheSupportActWithoutRewritingIt() {
        UUID gig = create(tm("tm1", "Harbour Hounds " + tag, "2026-11-13T20:30:00Z", "Harbour Hounds " + tag));

        // Doors at 19:00 UK (GMT in November), acts only in the title, sold out, a promoter in front.
        SourceGig site = site(
                "ev1",
                "SOLD OUT: Club Fandango presents Harbour Hounds " + tag + " + Night Moths " + tag + " (18+)",
                "2026-11-13T19:00:00Z");
        RunStats stats = new RunStats();
        assertThat(resolver.ingest(site, stats, SEEN)).isEqualTo(Outcome.MERGED);
        assertThat(stats.filled).isEqualTo(1);
        assertThat(sources(gig)).containsExactlyInAnyOrder("TICKETMASTER", "VENUE");
        // Ticketmaster still owns the fields; the venue added the support act it did not list.
        assertThat(dsl.fetchValue(dsl.select(GIG.TITLE).from(GIG).where(GIG.ID.eq(gig))))
                .isEqualTo("Harbour Hounds " + tag);
        assertThat(bill(gig)).containsExactly("Harbour Hounds " + tag, "Night Moths " + tag);

        // The next run finds it by its own id and changes nothing.
        RunStats again = new RunStats();
        assertThat(resolver.ingest(site, again, SEEN)).isEqualTo(Outcome.UNCHANGED);
        assertThat(again.gigs.matchedByExternalId).isEqualTo(1);

        // Coverage sees the venue's listing as one Ticketmaster also has; a venue-only show as a miss.
        assertThat(resolver.ingest(site("ev-only", "Rope Burns " + tag, "2026-11-14T19:00:00Z"), new RunStats(), SEEN))
                .isEqualTo(Outcome.CREATED);
        assertThat(catalog
                        .listingsAlsoListedBy(
                                "VENUE",
                                "TICKETMASTER",
                                Instant.parse("2026-11-13T00:00:00Z"),
                                Instant.parse("2026-11-15T00:00:00Z"))
                        .stream()
                        .filter(l -> l.externalId().startsWith(siteVenue.externalId() + ":"))
                        .map(l -> l.title() + "=" + l.alsoListed()))
                .containsExactly("Harbour Hounds " + tag + "=true", "Rope Burns " + tag + "=false");
    }

    @Test
    void aNameWithAPlusInItStaysOneActWhenTheCatalogKnowsIt() {
        // "Moon + The Tides" is a band the catalog knows (from Ticketmaster at another venue).
        SourceVenue elsewhere =
                new SourceVenue("tmv2-" + tag, "Elsewhere " + tag, null, "London", null, 58.0, -1.5, "Europe/London");
        create(new SourceGig(
                "TICKETMASTER",
                "tm-moon-" + tag,
                null,
                "Moon + The Tides " + tag,
                Instant.parse("2026-11-01T20:00:00Z"),
                null,
                "SCHEDULED",
                null,
                null,
                null,
                null,
                null,
                elsewhere,
                List.of(new SourceArtist("tma-moon-" + tag, "Moon + The Tides " + tag, null, List.of(), null))));

        SourceGig site = site("ev-moon", "Moon + The Tides " + tag, "2026-11-20T19:00:00Z");
        RunStats stats = new RunStats();
        UUID gig = gigOf(resolver.ingest(site, stats, SEEN), "VENUE", "ev-moon");
        assertThat(bill(gig)).containsExactly("Moon + The Tides " + tag);
        assertThat(stats.artists.created).isZero();
        assertThat(dsl.fetchCount(ARTIST, ARTIST.NAME.eq("Moon"))).isZero();
    }

    @Test
    void aWrongEarlierParseDoesNotConfirmItself() {
        // A first run stored "Red Moth/Blue Owl" as one act (no source vouches for it)...
        SourceGig once = new SourceGig(
                "VENUE",
                siteVenue.externalId() + ":old-" + tag,
                null,
                "Red Moth " + tag + "/Blue Owl " + tag,
                Instant.parse("2026-11-27T19:00:00Z"),
                null,
                "SCHEDULED",
                null,
                null,
                null,
                null,
                null,
                siteVenue,
                List.of(new SourceArtist("", "Red Moth " + tag + "/Blue Owl " + tag, null, List.of(), null)),
                TimePrecision.DOORS);
        assertThat(resolver.ingest(once, new RunStats(), SEEN)).isEqualTo(Outcome.CREATED);
        // ...the better parse splits it, and the old one-act name does not pull the acts back together.
        UUID gig = gigOf(
                resolver.ingest(
                        site("new", "Red Moth " + tag + "/Blue Owl " + tag, "2026-11-28T19:00:00Z"),
                        new RunStats(),
                        SEEN),
                "VENUE",
                "new");
        assertThat(bill(gig)).containsExactly("Red Moth " + tag, "Blue Owl " + tag);
    }

    @Test
    void anAlmostMatchingActIsKeptApartAndLoggedForReviewOnce() {
        UUID tmGig = create(tm("tm-pk", "Pale Kite " + tag, "2026-11-14T20:00:00Z", "Pale Kite " + tag));

        SourceGig site = site("ev-pk", "Pale Kites " + tag, "2026-11-14T19:00:00Z");
        RunStats stats = new RunStats();
        assertThat(resolver.ingest(site, stats, SEEN)).isEqualTo(Outcome.CREATED);
        assertThat(stats.flaggedForReview).isEqualTo(1);
        UUID siteGig = gigId("VENUE", "ev-pk");
        assertThat(siteGig).isNotEqualTo(tmGig);
        var review = dsl.selectFrom(MATCH_REVIEW)
                .where(MATCH_REVIEW.EXTERNAL_ID.eq(siteVenue.externalId() + ":ev-pk"))
                .fetchSingle();
        assertThat(review.getCandidateGigId()).isEqualTo(tmGig);
        assertThat(review.getGigId()).isEqualTo(siteGig);
        assertThat(review.getStatus()).isEqualTo("OPEN");
        assertThat(review.getActSimilarity()).isBetween(0.5f, 1.0f);
    }

    @Test
    void aFanAddedGigIsTakenOverByTheTicketmasterListingOfTheSameNight() {
        // A fan added the gig with only a date (stored at 20:00).
        SourceGig fan = new SourceGig(
                "USER",
                "fan-" + tag,
                null,
                "Glass Harbour " + tag,
                Instant.parse("2026-11-15T20:00:00Z"),
                null,
                "SCHEDULED",
                null,
                null,
                null,
                null,
                null,
                tmVenue,
                List.of(new SourceArtist("", "Glass Harbour " + tag, null, List.of(), null)),
                TimePrecision.DATE_ONLY);
        UUID gig = create(fan);

        // Ticketmaster lists the matinee at 15:00 with a price and a ticket link.
        SourceGig tm =
                tm("tm-gh", "Glass Harbour " + tag + ": Matinee", "2026-11-15T15:00:00Z", "Glass Harbour " + tag);
        assertThat(resolver.ingest(tm, new RunStats(), SEEN)).isEqualTo(Outcome.MERGED);
        assertThat(dsl.fetchValue(dsl.select(GIG.TITLE).from(GIG).where(GIG.ID.eq(gig))))
                .isEqualTo("Glass Harbour " + tag + ": Matinee");
        assertThat(dsl.select(GIG_SOURCE.SOURCE)
                        .from(GIG_SOURCE)
                        .where(GIG_SOURCE.GIG_ID.eq(gig))
                        .and(GIG_SOURCE.PRIMARY_LISTING.isTrue())
                        .fetchSingle(GIG_SOURCE.SOURCE))
                .isEqualTo("TICKETMASTER");
    }

    @Test
    void twoDifferentGigsOnOneNightAtOneVenueStayApartWithoutAQuestion() {
        UUID early = create(tm("tm-early", "Brass Owls " + tag, "2026-11-21T18:00:00Z", "Brass Owls " + tag));
        RunStats stats = new RunStats();
        assertThat(resolver.ingest(
                        site("ev-late", "Velvet Static " + tag + " + Dune Sisters " + tag, "2026-11-21T22:00:00Z"),
                        stats,
                        SEEN))
                .isEqualTo(Outcome.CREATED);
        assertThat(gigId("VENUE", "ev-late")).isNotEqualTo(early);
        assertThat(stats.flaggedForReview).isZero();
    }

    @Test
    void aShowTicketmasterListsUnderAnotherRoomOfTheBuildingIsFoundOnceTheRoomsAreSiblings() {
        // Ticketmaster's second row for the building ("Downstairs at …"), a kilometre off on the map.
        SourceVenue downstairs = new SourceVenue(
                "tmv-down-" + tag,
                "Downstairs at The Lantern " + tag,
                null,
                "London",
                null,
                tmVenue.lat() + 0.01,
                -0.5,
                "Europe/London");
        create(tm("tm-main", "Glass Owls " + tag, "2026-11-01T20:00:00Z", "Glass Owls " + tag));
        UUID before =
                create(tmAt(downstairs, "tm-d1", "Rope Burns " + tag, "2026-11-05T20:00:00Z", "Rope Burns " + tag));
        UUID after =
                create(tmAt(downstairs, "tm-d2", "Pale Kites " + tag, "2026-11-06T20:00:00Z", "Pale Kites " + tag));

        // Not yet siblings: the venue's own listing of the downstairs show becomes a second gig.
        assertThat(resolver.ingest(site("ev-d1", "Rope Burns " + tag, "2026-11-05T19:00:00Z"), new RunStats(), SEEN))
                .isEqualTo(Outcome.CREATED);
        assertThat(gigId("VENUE", "ev-d1")).isNotEqualTo(before);

        catalog.linkSiblings(
                venueOf("TICKETMASTER", tmVenue.externalId()), venueOf("TICKETMASTER", downstairs.externalId()));

        // Siblings: the same kind of listing joins the downstairs gig, which stays downstairs.
        assertThat(resolver.ingest(site("ev-d2", "Pale Kites " + tag, "2026-11-06T19:00:00Z"), new RunStats(), SEEN))
                .isEqualTo(Outcome.MERGED);
        assertThat(sources(after)).containsExactlyInAnyOrder("TICKETMASTER", "VENUE");
        assertThat(dsl.fetchValue(dsl.select(GIG.VENUE_ID).from(GIG).where(GIG.ID.eq(after))))
                .isEqualTo(venueOf("TICKETMASTER", downstairs.externalId()));
    }

    @Test
    void twoShowsTheSiteListsAtTheSameTimeStayTwoGigs() {
        // Both rooms open at 19:00; the second listing must not fold into the first night's gig.
        assertThat(resolver.ingest(
                        site("ev-up", "COCKNEY REBELS " + tag, "2026-10-17T18:00:00Z"), new RunStats(), SEEN))
                .isEqualTo(Outcome.CREATED);
        assertThat(resolver.ingest(site("ev-down", "ACRE " + tag, "2026-10-17T18:00:00Z"), new RunStats(), SEEN))
                .isEqualTo(Outcome.CREATED);
        assertThat(gigId("VENUE", "ev-up")).isNotEqualTo(gigId("VENUE", "ev-down"));
        assertThat(bill(gigId("VENUE", "ev-up"))).containsExactly("COCKNEY REBELS " + tag);
    }

    @Test
    void siblingsOfASiblingAreSiblings() {
        UUID a = catalogVenue("a");
        UUID b = catalogVenue("b");
        UUID c = catalogVenue("c");
        catalog.linkSiblings(a, b);
        catalog.linkSiblings(b, c);
        catalog.linkSiblings(c, a); // already linked: nothing changes
        assertThat(dsl.select(VENUE_SIBLING.SIBLING_ID)
                        .from(VENUE_SIBLING)
                        .where(VENUE_SIBLING.VENUE_ID.eq(a))
                        .fetch(VENUE_SIBLING.SIBLING_ID))
                .containsExactlyInAnyOrder(b, c);
        assertThat(dsl.select(VENUE_SIBLING.SIBLING_ID)
                        .from(VENUE_SIBLING)
                        .where(VENUE_SIBLING.VENUE_ID.eq(c))
                        .fetch(VENUE_SIBLING.SIBLING_ID))
                .containsExactlyInAnyOrder(a, b);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private UUID venueOf(String source, String externalId) {
        return dsl.select(VENUE_EXTERNAL_ID.VENUE_ID)
                .from(VENUE_EXTERNAL_ID)
                .where(VENUE_EXTERNAL_ID.SOURCE.eq(source))
                .and(VENUE_EXTERNAL_ID.EXTERNAL_ID.eq(externalId))
                .fetchSingle(VENUE_EXTERNAL_ID.VENUE_ID);
    }

    private UUID catalogVenue(String name) {
        return catalog.createVenue(
                new VenueDraft(name + " " + tag, null, "London", null, 61 + Math.random(), -0.5, "Europe/London"));
    }

    private SourceGig tm(String id, String title, String startsAt, String... acts) {
        return tmAt(tmVenue, id, title, startsAt, acts);
    }

    private SourceGig tmAt(SourceVenue venue, String id, String title, String startsAt, String... acts) {
        return new SourceGig(
                "TICKETMASTER",
                id + "-" + tag,
                "https://tickets.example.com/" + id,
                title,
                Instant.parse(startsAt),
                null,
                "SCHEDULED",
                "https://tickets.example.com/" + id,
                null,
                null,
                null,
                null,
                venue,
                Arrays.stream(acts)
                        .map(a -> new SourceArtist("tma-" + a, a, null, List.of(), null))
                        .toList());
    }

    /** A venue-site listing: names only (blank external ids), parsed out of the title, a doors time. */
    private SourceGig site(String id, String title, String doorsAt) {
        List<SourceArtist> acts = TitleParser.parse(title).acts().stream()
                .map(name -> new SourceArtist("", name, null, List.of(), null))
                .toList();
        return new SourceGig(
                "VENUE",
                siteVenue.externalId() + ":" + id,
                "https://lantern.example.com/events/" + id,
                title,
                Instant.parse(doorsAt),
                null,
                "SCHEDULED",
                null,
                null,
                null,
                null,
                null,
                siteVenue,
                acts,
                TimePrecision.DOORS);
    }

    private UUID create(SourceGig gig) {
        assertThat(resolver.ingest(gig, new RunStats(), SEEN)).isEqualTo(Outcome.CREATED);
        return gigId(gig.source(), gig.externalId());
    }

    private UUID gigOf(Outcome outcome, String source, String id) {
        assertThat(outcome).isIn(Outcome.CREATED, Outcome.MERGED);
        return gigId(source, id);
    }

    private UUID gigId(String source, String externalId) {
        String id = source.equals("VENUE") ? siteVenue.externalId() + ":" + externalId : externalId;
        return dsl.select(GIG_SOURCE.GIG_ID)
                .from(GIG_SOURCE)
                .where(GIG_SOURCE.SOURCE.eq(source))
                .and(GIG_SOURCE.EXTERNAL_ID.eq(id))
                .fetchSingle(GIG_SOURCE.GIG_ID);
    }

    private List<String> sources(UUID gig) {
        return dsl.select(GIG_SOURCE.SOURCE)
                .from(GIG_SOURCE)
                .where(GIG_SOURCE.GIG_ID.eq(gig))
                .fetch(GIG_SOURCE.SOURCE);
    }

    private List<String> bill(UUID gig) {
        return dsl.select(ARTIST.NAME)
                .from(GIG_ARTIST)
                .join(ARTIST)
                .on(ARTIST.ID.eq(GIG_ARTIST.ARTIST_ID))
                .where(GIG_ARTIST.GIG_ID.eq(gig))
                .orderBy(GIG_ARTIST.BILLING_ORDER)
                .fetch(ARTIST.NAME);
    }
}
