package app.soundcheck.ingestion.internal.resolve;

import static app.soundcheck.shared.jooq.catalog.Tables.ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.ARTIST_EXTERNAL_ID;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_SOURCE;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE_EXTERNAL_ID;
import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.ingestion.internal.RunStats;
import app.soundcheck.ingestion.internal.resolve.EntityResolver.Outcome;
import app.soundcheck.ingestion.internal.source.CallBudget;
import app.soundcheck.ingestion.internal.source.GigSource;
import app.soundcheck.ingestion.internal.source.SourceArtist;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceVenue;
import app.soundcheck.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Entity resolution against real PostgreSQL (PostGIS + pg_trgm): the same fixtures twice → the second
 * pass changes nothing; a second source listing a gig one hour apart merges into one gig with two
 * sources; and each matching rule, including the cases that must <em>not</em> match.
 */
class EntityResolutionIT extends IntegrationTest {

    static final Instant SEEN = Instant.parse("2026-10-02T05:10:00Z");

    @Autowired
    EntityResolver resolver;

    @Autowired
    GigSource fixtures;

    @Autowired
    DSLContext dsl;

    @Test
    void ingestingTheSameFixturesTwiceChangesNothingTheSecondTime() {
        List<SourceGig> gigs = fixtureGigs();
        assertThat(gigs).hasSize(233);

        RunStats first = new RunStats();
        gigs.forEach(g -> resolver.ingest(g, first, SEEN));
        // Another test class may have ingested the fixtures into the shared database already.
        // The fixture lists two shows twice ("… - VIP Package", same venue, same start, same headliner):
        // those merge into the original gig, so 233 listings become 231 gigs.
        assertThat(first.created + first.merged + first.unchanged).isEqualTo(233);
        assertThat(first.merged).isLessThanOrEqualTo(2);
        assertThat(first.updated).isZero();

        RunStats second = new RunStats();
        gigs.forEach(g -> resolver.ingest(g, second, SEEN.plus(Duration.ofDays(1))));
        assertThat(second.unchanged).isEqualTo(233);
        assertThat(second.created).isZero();
        assertThat(second.updated).isZero();
        assertThat(second.merged).isZero();
        assertThat(second.venues.created).isZero();
        assertThat(second.artists.created).isZero();
        assertThat(second.gigs.matchedByExternalId).isEqualTo(233);
        assertThat(second.venues.matchedByExternalId).isEqualTo(233);
        assertThat(second.artists.matchedFuzzy).isZero();

        // One row per fixture gig, each seen by the second pass, each with a headliner and a location copy.
        assertThat(dsl.fetchCount(
                        GIG_SOURCE, GIG_SOURCE.SOURCE.eq("TICKETMASTER").and(GIG_SOURCE.EXTERNAL_ID.like("G5vsyn%"))))
                .isEqualTo(233);
        assertThat(dsl.fetchValue(
                        "select count(distinct gig_id) from catalog.gig_source where external_id like 'G5vsyn%'"))
                .isEqualTo(231L);
        assertThat(gigId("TICKETMASTER", "G5vsyn00205")).isEqualTo(gigId("TICKETMASTER", "G5vsyn00030"));
        assertThat(dsl.fetchCount(
                        GIG_SOURCE,
                        GIG_SOURCE
                                .EXTERNAL_ID
                                .like("G5vsyn%")
                                .and(GIG_SOURCE.LAST_SEEN_AT.lt(
                                        SEEN.plus(Duration.ofDays(1)).atOffset(java.time.ZoneOffset.UTC)))))
                .isZero();
        assertThat(dsl.fetchCount(VENUE_EXTERNAL_ID, VENUE_EXTERNAL_ID.EXTERNAL_ID.like("KovZsyn%")))
                .isBetween(40, 46); // 46 fixture venues; a few host no mappable gig
        assertThat(dsl.fetchCount(ARTIST_EXTERNAL_ID, ARTIST_EXTERNAL_ID.EXTERNAL_ID.like("K8vZsyn%")))
                .isBetween(100, 150);
    }

    @Test
    void aSecondSourceListingTheSameGigAnHourApartMergesIntoOneGigWithTwoSources() {
        SourceGig original = gig(
                "TICKETMASTER",
                "tm-merge-1",
                "Harbour Wolves",
                "2026-11-06T19:30:00Z",
                venue("tm-v-merge", "The Tin Exchange", "M4 1LE", 53.4841, -2.2360),
                artist("tm-a-merge", "Harbour Wolves", "Rock"));
        assertThat(resolver.ingest(original, new RunStats(), SEEN)).isEqualTo(Outcome.CREATED);

        // Skiddle's view: its own ids, the venue named slightly differently 40 m away, doors-time start.
        SourceGig second = gig(
                "SKIDDLE",
                "sk-merge-1",
                "Harbour Wolves + support",
                "2026-11-06T18:30:00Z",
                venue("sk-v-merge", "Tin Exchange Manchester", "M4 1LE", 53.48445, -2.2361),
                artist("sk-a-merge", "HARBOUR WOLVES"));
        RunStats stats = new RunStats();
        assertThat(resolver.ingest(second, stats, SEEN)).isEqualTo(Outcome.MERGED);
        assertThat(stats.merged).isEqualTo(1);
        assertThat(stats.venues.matchedFuzzy).isEqualTo(1);
        assertThat(stats.venues.created).isZero();
        assertThat(stats.artists.matchedFuzzy).isEqualTo(1); // exact normalised name
        assertThat(stats.artists.created).isZero();
        assertThat(stats.created).isZero();

        UUID gigId = gigId("TICKETMASTER", "tm-merge-1");
        assertThat(gigId("SKIDDLE", "sk-merge-1")).isEqualTo(gigId);
        assertThat(dsl.select(GIG_SOURCE.SOURCE)
                        .from(GIG_SOURCE)
                        .where(GIG_SOURCE.GIG_ID.eq(gigId))
                        .fetch(GIG_SOURCE.SOURCE))
                .containsExactlyInAnyOrder("TICKETMASTER", "SKIDDLE");
        // The first source's fields stand.
        assertThat(dsl.select(GIG.TITLE).from(GIG).where(GIG.ID.eq(gigId)).fetchOne(GIG.TITLE))
                .isEqualTo("Harbour Wolves");

        // Seen again from Skiddle: found by its own id now, and it may not rewrite Ticketmaster's fields.
        RunStats again = new RunStats();
        assertThat(resolver.ingest(second, again, SEEN)).isEqualTo(Outcome.UNCHANGED);
        assertThat(again.gigs.matchedByExternalId).isEqualTo(1);
        assertThat(again.venues.matchedByExternalId).isEqualTo(1);
        assertThat(again.artists.matchedByExternalId).isEqualTo(1);
        assertThat(dsl.select(GIG.TITLE).from(GIG).where(GIG.ID.eq(gigId)).fetchOne(GIG.TITLE))
                .isEqualTo("Harbour Wolves");

        // Four hours apart is a different show (matinee + evening): no merge.
        SourceGig late = gig(
                "SKIDDLE",
                "sk-merge-2",
                "Harbour Wolves",
                "2026-11-06T23:45:00Z",
                venue("sk-v-merge", "Tin Exchange Manchester", "M4 1LE", 53.48445, -2.2361),
                artist("sk-a-merge", "HARBOUR WOLVES"));
        assertThat(resolver.ingest(late, new RunStats(), SEEN)).isEqualTo(Outcome.CREATED);
    }

    @Test
    void whenTheOwningListingArrivesSecondItTakesOverAtOnceSoReRunsAreStable() {
        SourceVenue venue = venue("tm-v-owner", "Anchor Ballroom", "L1 4BX", 53.4040, -2.9800);
        SourceArtist act = artist("tm-a-owner", "Golden Parade", "Pop");
        // The "VIP" listing has the larger id and is seen first; the plain listing (smaller id) owns the gig.
        SourceGig vip =
                gig("TICKETMASTER", "tm-owner-9", "Golden Parade - VIP Package", "2026-11-15T19:00:00Z", venue, act);
        SourceGig plain = gig("TICKETMASTER", "tm-owner-1", "Golden Parade", "2026-11-15T19:00:00Z", venue, act);

        assertThat(resolver.ingest(vip, new RunStats(), SEEN)).isEqualTo(Outcome.CREATED);
        assertThat(resolver.ingest(plain, new RunStats(), SEEN)).isEqualTo(Outcome.MERGED);
        UUID gigId = gigId("TICKETMASTER", "tm-owner-1");
        assertThat(gigId("TICKETMASTER", "tm-owner-9")).isEqualTo(gigId);
        assertThat(dsl.select(GIG.TITLE).from(GIG).where(GIG.ID.eq(gigId)).fetchOne(GIG.TITLE))
                .isEqualTo("Golden Parade");

        // The next run sees both listings again: nothing changes, in either order.
        assertThat(resolver.ingest(vip, new RunStats(), SEEN)).isEqualTo(Outcome.UNCHANGED);
        assertThat(resolver.ingest(plain, new RunStats(), SEEN)).isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    void oneSourceListingTwoShowsOnOneNightKeepsThemApartButMergesPackagesAndExactDuplicates() {
        SourceVenue venue = venue("tm-v-shows", "Willow Social", "EH1 3AA", 55.9510, -3.1900);
        SourceArtist act = artist("tm-a-shows", "Nubian Parade", "Jazz");

        // An early and a late show: same act, same venue, 2.5 hours apart, two Ticketmaster ids → two gigs.
        // (The first live run merged 31 such pairs; a source's own ids mean something.)
        assertThat(resolver.ingest(
                        gig("TICKETMASTER", "tm-shows-early", "Nubian Parade", "2026-11-18T18:00:00Z", venue, act),
                        new RunStats(),
                        SEEN))
                .isEqualTo(Outcome.CREATED);
        assertThat(resolver.ingest(
                        gig("TICKETMASTER", "tm-shows-late", "Nubian Parade", "2026-11-18T20:30:00Z", venue, act),
                        new RunStats(),
                        SEEN))
                .isEqualTo(Outcome.CREATED);
        assertThat(gigId("TICKETMASTER", "tm-shows-late")).isNotEqualTo(gigId("TICKETMASTER", "tm-shows-early"));

        // The same show listed again at the same instant → merged.
        assertThat(resolver.ingest(
                        gig("TICKETMASTER", "tm-shows-early-dup", "Nubian Parade", "2026-11-18T18:00:00Z", venue, act),
                        new RunStats(),
                        SEEN))
                .isEqualTo(Outcome.MERGED);
        assertThat(gigId("TICKETMASTER", "tm-shows-early-dup")).isEqualTo(gigId("TICKETMASTER", "tm-shows-early"));

        // A package listing dated at the doors time (30 minutes before the late show) → merged into the
        // nearest show, which keeps its own title and time because the plain listing stays primary.
        assertThat(resolver.ingest(
                        gig(
                                "TICKETMASTER",
                                "tm-shows-vip",
                                "Nubian Parade - VIP Package",
                                "2026-11-18T20:00:00Z",
                                venue,
                                act),
                        new RunStats(),
                        SEEN))
                .isEqualTo(Outcome.MERGED);
        UUID late = gigId("TICKETMASTER", "tm-shows-late");
        assertThat(gigId("TICKETMASTER", "tm-shows-vip")).isEqualTo(late);
        var row = dsl.selectFrom(GIG).where(GIG.ID.eq(late)).fetchSingle();
        assertThat(row.getTitle()).isEqualTo("Nubian Parade");
        assertThat(row.getStartsAt().toInstant()).isEqualTo(Instant.parse("2026-11-18T20:30:00Z"));
        assertThat(dsl.fetchCount(GIG_SOURCE, GIG_SOURCE.GIG_ID.eq(late).and(GIG_SOURCE.PRIMARY_LISTING.isTrue())))
                .isEqualTo(1);

        assertThat(EntityResolver.isPackage("ABBA Voyage - VIP Premium Package"))
                .isTrue();
        assertThat(EntityResolver.isPackage("The Pussycat Dolls - Venue Premium Tickets"))
                .isTrue();
        assertThat(EntityResolver.isPackage("Meet & Greet Upgrade: Pale Kites")).isTrue();
        assertThat(EntityResolver.isPackage("Premiumfunk Orchestra")).isFalse();
        assertThat(EntityResolver.isPackage("Nubian Parade")).isFalse();
    }

    @Test
    void aShowOnlyAPackageListingAnnouncesKeepsItsTitleAndThePlainListingStillTakesOver() {
        // Ticketmaster sometimes has only the premium listing of a show, dated at the doors time.
        SourceVenue venue = venue("tm-v-premium", "Granite Arena", "AB23 8BL", 57.1955, -2.0768);
        SourceArtist act = artist("tm-a-premium", "Glass Harbour", "Pop");
        assertThat(resolver.ingest(
                        gig(
                                "TICKETMASTER",
                                "tm-premium-only",
                                "Glass Harbour: Venue Premium Tickets",
                                "2026-11-21T18:30:00Z",
                                venue,
                                act),
                        new RunStats(),
                        SEEN))
                .isEqualTo(Outcome.CREATED);
        UUID gigId = gigId("TICKETMASTER", "tm-premium-only");
        assertThat(dsl.select(GIG.TITLE).from(GIG).where(GIG.ID.eq(gigId)).fetchOne(GIG.TITLE))
                .isEqualTo("Glass Harbour");
        assertThat(dsl.select(GIG_SOURCE.PACKAGE_LISTING)
                        .from(GIG_SOURCE)
                        .where(GIG_SOURCE.EXTERNAL_ID.eq("tm-premium-only"))
                        .fetchOne(GIG_SOURCE.PACKAGE_LISTING))
                .isTrue();

        // The plain listing appears later, half an hour after the doors time: still the same show (the
        // listing, not the stored title, says the gig follows a package), and it takes over the gig.
        assertThat(resolver.ingest(
                        gig("TICKETMASTER", "tm-premium-plain", "Glass Harbour", "2026-11-21T19:00:00Z", venue, act),
                        new RunStats(),
                        SEEN))
                .isEqualTo(Outcome.MERGED);
        assertThat(gigId("TICKETMASTER", "tm-premium-plain")).isEqualTo(gigId);
        var row = dsl.selectFrom(GIG).where(GIG.ID.eq(gigId)).fetchSingle();
        assertThat(row.getTitle()).isEqualTo("Glass Harbour");
        assertThat(row.getStartsAt().toInstant()).isEqualTo(Instant.parse("2026-11-21T19:00:00Z"));
        assertThat(dsl.select(GIG_SOURCE.EXTERNAL_ID)
                        .from(GIG_SOURCE)
                        .where(GIG_SOURCE.GIG_ID.eq(gigId))
                        .and(GIG_SOURCE.PRIMARY_LISTING.isTrue())
                        .fetch(GIG_SOURCE.EXTERNAL_ID))
                .containsExactly("tm-premium-plain");
    }

    @Test
    void aVenueSitesAndActIsSplitOnlyWhenBothHalvesAreKnownArtistsAndTheWholeIsNot() {
        // The halves exist as Ticketmaster artists (vouched for); "Jesse" only from a venue title.
        SourceVenue arena = venue("tm-v-amp", "Amp Hall", "N7 6AA", 51.5530, -0.1180);
        String[][] known = {
            {"tm-a-hedpe", "(Hed) P.E."},
            {"tm-a-cold", "Cold"},
            {"tm-a-skids", "The Skids"},
            {"tm-a-toh", "Theatre of Hate"},
            {"tm-a-joy", "The Joy"},
            {"tm-a-echo", "Echo & the Bunnymen"}
        };
        for (int i = 0; i < known.length; i++) {
            resolver.ingest(
                    gig(
                            "TICKETMASTER",
                            "tm-amp-" + i,
                            known[i][1],
                            "2026-12-0" + (i + 1) + "T19:30:00Z",
                            arena,
                            artist(known[i][0], known[i][1], "Rock")),
                    new RunStats(),
                    SEEN);
        }
        SourceVenue site = venue("site-amp", "Sound Shed", "N1 9AA", 51.5390, -0.1030);
        resolver.ingest(
                venueGig("site-amp-jesse", "Jesse", "2026-12-10T19:00:00Z", site, "Jesse"), new RunStats(), SEEN);

        assertThat(actsOf(venueGig("site-amp-1", "HED PE & COLD", "2026-12-11T19:00:00Z", site, "HED PE & COLD")))
                .containsExactly("(Hed) P.E.", "Cold");
        assertThat(actsOf(venueGig(
                        "site-amp-2",
                        "SKIDS & THEATRE OF HATE",
                        "2026-12-12T19:00:00Z",
                        site,
                        "SKIDS & THEATRE OF HATE")))
                .containsExactly("The Skids", "Theatre of Hate");
        // Only one half is vouched for ("Jesse" came from a venue title): one act.
        assertThat(actsOf(venueGig("site-amp-3", "Jesse & Joy", "2026-12-13T19:00:00Z", site, "Jesse & Joy")))
                .containsExactly("Jesse & Joy");
        // "& the" is always one act, even when the whole is a known artist.
        assertThat(actsOf(venueGig(
                        "site-amp-4", "Echo & the Bunnymen", "2026-12-14T19:00:00Z", site, "Echo & the Bunnymen")))
                .containsExactly("Echo & the Bunnymen");
        assertThat(actsOf(venueGig(
                        "site-amp-5",
                        "Geraint Watkins & the Mosquitoes",
                        "2026-12-15T19:00:00Z",
                        site,
                        "Geraint Watkins & the Mosquitoes")))
                .containsExactly("Geraint Watkins & the Mosquitoes");
        assertThat(actsOf(venueGig(
                        "site-amp-6", "THE FIERCE & THE DEAD", "2026-12-16T19:00:00Z", site, "THE FIERCE & THE DEAD")))
                .containsExactly("THE FIERCE & THE DEAD");
    }

    /** Ingests a venue-site listing and returns its gig's line-up, headliner first. */
    private List<String> actsOf(SourceGig gig) {
        resolver.ingest(gig, new RunStats(), SEEN);
        return dsl.select(ARTIST.NAME)
                .from(GIG_ARTIST)
                .join(ARTIST)
                .on(ARTIST.ID.eq(GIG_ARTIST.ARTIST_ID))
                .where(GIG_ARTIST.GIG_ID.eq(gigId("VENUE", gig.externalId())))
                .orderBy(GIG_ARTIST.BILLING_ORDER)
                .fetch(ARTIST.NAME);
    }

    private static SourceGig venueGig(String externalId, String title, String startsAt, SourceVenue venue, String act) {
        return gig("VENUE", externalId, title, startsAt, venue, new SourceArtist("", act, null, List.of(), null));
    }

    @Test
    void whenThePrimaryListingDisappearsAnotherListingTakesOver() {
        SourceVenue venue = venue("tm-v-gone", "Harbour Cellar", "CF10 1AA", 51.4790, -3.1790);
        SourceArtist act = artist("tm-a-gone", "Slow Embers", "Folk");
        SourceGig first = gig("TICKETMASTER", "tm-gone-a", "Slow Embers", "2026-11-25T19:30:00Z", venue, act);
        SourceGig second = gig("TICKETMASTER", "tm-gone-z", "Slow Embers", "2026-11-25T19:30:00Z", venue, act);
        resolver.ingest(first, new RunStats(), SEEN);
        assertThat(resolver.ingest(second, new RunStats(), SEEN)).isEqualTo(Outcome.MERGED);

        // The next day the second listing reports a cancellation, but it is not the primary: ignored.
        SourceGig cancelled = new SourceGig(
                second.source(),
                second.externalId(),
                second.url(),
                second.title(),
                second.startsAt(),
                null,
                "CANCELLED",
                second.ticketUrl(),
                null,
                null,
                null,
                null,
                venue,
                second.artists());
        Instant nextDay = SEEN.plus(Duration.ofDays(1));
        resolver.ingest(first, new RunStats(), nextDay);
        assertThat(resolver.ingest(cancelled, new RunStats(), nextDay)).isEqualTo(Outcome.UNCHANGED);

        // The first listing then vanishes from the source. Four days on, the surviving listing becomes
        // primary and its cancellation reaches the gig.
        assertThat(resolver.ingest(cancelled, new RunStats(), nextDay.plus(Duration.ofDays(4))))
                .isEqualTo(Outcome.UPDATED);
        UUID gigId = gigId("TICKETMASTER", "tm-gone-z");
        assertThat(dsl.select(GIG.STATUS).from(GIG).where(GIG.ID.eq(gigId)).fetchOne(GIG.STATUS))
                .isEqualTo("CANCELLED");
        assertThat(dsl.select(GIG_SOURCE.EXTERNAL_ID)
                        .from(GIG_SOURCE)
                        .where(GIG_SOURCE.GIG_ID.eq(gigId))
                        .and(GIG_SOURCE.PRIMARY_LISTING.isTrue())
                        .fetch(GIG_SOURCE.EXTERNAL_ID))
                .containsExactly("tm-gone-z");
    }

    @Test
    void aVenueFirstSeenWithoutCoordinatesGetsThemLaterAndSoDoItsGigs() {
        SourceArtist act = artist("tm-a-coords", "Quiet Atlas", "Rock");
        resolver.ingest(
                gig(
                        "TICKETMASTER",
                        "tm-coords-1",
                        "Quiet Atlas",
                        "2026-11-27T19:30:00Z",
                        venue("tm-v-coords", "Paper Works", "S1 2BX", null, null),
                        act),
                new RunStats(),
                SEEN);
        UUID gigId = gigId("TICKETMASTER", "tm-coords-1");
        assertThat(dsl.fetchValue("select location is null from catalog.gig where id = ?", gigId))
                .isEqualTo(true);

        resolver.ingest(
                gig(
                        "TICKETMASTER",
                        "tm-coords-2",
                        "Quiet Atlas",
                        "2026-11-28T19:30:00Z",
                        venue("tm-v-coords", "Paper Works", "S1 2BX", 53.3811, -1.4701),
                        act),
                new RunStats(),
                SEEN);
        assertThat(dsl.fetchValue("select ST_Y(location::geometry) from catalog.gig where id = ?", gigId))
                .isEqualTo(53.3811);
    }

    @Test
    void aChangedGigIsUpdatedAndAnUnchangedOneIsNot() {
        SourceVenue venue = venue("tm-v-change", "Cinder Depot", "LS2 7EY", 53.8008, -1.5491);
        SourceGig before = gig(
                "TICKETMASTER",
                "tm-change-1",
                "Pale Kites",
                "2026-11-10T19:00:00Z",
                venue,
                artist("tm-a-change-1", "Pale Kites", "Folk"),
                artist("tm-a-change-2", "Slow Bloom", "Folk"));
        assertThat(resolver.ingest(before, new RunStats(), SEEN)).isEqualTo(Outcome.CREATED);
        assertThat(resolver.ingest(before, new RunStats(), SEEN)).isEqualTo(Outcome.UNCHANGED);

        // Cancelled, and the support act dropped off the bill.
        SourceGig after = new SourceGig(
                before.source(),
                before.externalId(),
                before.url(),
                before.title(),
                before.startsAt(),
                null,
                "CANCELLED",
                before.ticketUrl(),
                new BigDecimal("12.50"),
                new BigDecimal("15.00"),
                "GBP",
                null,
                venue,
                List.of(before.artists().getFirst()));
        RunStats stats = new RunStats();
        assertThat(resolver.ingest(after, stats, SEEN)).isEqualTo(Outcome.UPDATED);
        assertThat(stats.updated).isEqualTo(1);

        UUID gigId = gigId("TICKETMASTER", "tm-change-1");
        var row = dsl.selectFrom(GIG).where(GIG.ID.eq(gigId)).fetchOne();
        assertThat(row.getStatus()).isEqualTo("CANCELLED");
        assertThat(row.getPriceMin()).isEqualByComparingTo("12.50");
        assertThat(dsl.fetchCount(GIG_ARTIST, GIG_ARTIST.GIG_ID.eq(gigId))).isEqualTo(1);
        assertThat(dsl.fetchValue("select ST_Y(location::geometry) from catalog.gig where id = ?", gigId))
                .isEqualTo(53.8008); // location copied from the venue
    }

    @Test
    void artistsMatchByMbidThenExactNameThenVeryCloseNameWithACorroboratingGenreOrVenue() {
        SourceVenue venue = venue("tm-v-artists", "Ember Cellar", "BS1 4DJ", 51.4530, -2.5950);
        UUID mbid = UUID.fromString("0c1f2f3a-5b6c-4d7e-8f90-a1b2c3d4e5f6");
        resolver.ingest(
                gig(
                        "TICKETMASTER",
                        "tm-artists-1",
                        "Brilliant Corners Quartet",
                        "2026-11-12T20:00:00Z",
                        venue,
                        new SourceArtist("tm-a-bcq", "The Brilliant Corners Quartet", mbid, List.of("Jazz"), null),
                        artist("tm-a-oasis", "Oasis", "Rock")),
                new RunStats(),
                SEEN);
        UUID quartet = artistId("TICKETMASTER", "tm-a-bcq");
        UUID oasis = artistId("TICKETMASTER", "tm-a-oasis");
        SourceVenue elsewhere = venue("sk-v-artists", "Orchard Assembly", "NE1 6QG", 54.9720, -1.6130);

        // (2) MBID: a different spelling from another source, same MusicBrainz id.
        RunStats byMbid = new RunStats();
        resolver.ingest(
                gig(
                        "SKIDDLE",
                        "sk-artists-1",
                        "BCQ",
                        "2026-12-01T20:00:00Z",
                        elsewhere,
                        new SourceArtist("sk-a-bcq", "Brilliant Corners 4tet", mbid, List.of(), null)),
                byMbid,
                SEEN);
        assertThat(artistId("SKIDDLE", "sk-a-bcq")).isEqualTo(quartet);
        assertThat(byMbid.artists.matchedFuzzy).isEqualTo(1);

        // (3) exact normalised name: "the" and case do not matter.
        resolver.ingest(
                gig(
                        "SKIDDLE",
                        "sk-artists-2",
                        "BCQ",
                        "2026-12-02T20:00:00Z",
                        elsewhere,
                        artist("sk-a-bcq-2", "brilliant corners quartet")),
                new RunStats(),
                SEEN);
        assertThat(artistId("SKIDDLE", "sk-a-bcq-2")).isEqualTo(quartet);

        // (4) one letter off + a shared genre (Jazz) → the same artist.
        resolver.ingest(
                gig(
                        "SKIDDLE",
                        "sk-artists-3",
                        "BCQ",
                        "2026-12-03T20:00:00Z",
                        elsewhere,
                        artist("sk-a-bcq-3", "Brilliant Corners Quartett", "Jazz")),
                new RunStats(),
                SEEN);
        assertThat(artistId("SKIDDLE", "sk-a-bcq-3")).isEqualTo(quartet);

        // (4) one letter off, no shared genre, a venue the quartet has never played → a new artist.
        SourceVenue unrelated = venue("sk-v-artists-2", "Signal Yard", "G1 1XQ", 55.8600, -4.2500);
        RunStats noCorroboration = new RunStats();
        resolver.ingest(
                gig(
                        "SKIDDLE",
                        "sk-artists-4",
                        "BCQ",
                        "2026-12-04T20:00:00Z",
                        unrelated,
                        artist("sk-a-bcq-4", "Brilliant Corners Quartets", "Techno")),
                noCorroboration,
                SEEN);
        assertThat(artistId("SKIDDLE", "sk-a-bcq-4")).isNotEqualTo(quartet);
        assertThat(noCorroboration.artists.created).isEqualTo(1);

        // (4) one letter off, no genre, but at a venue the quartet has played → the same artist.
        resolver.ingest(
                gig(
                        "SKIDDLE",
                        "sk-artists-5",
                        "BCQ",
                        "2026-12-05T20:00:00Z",
                        venue,
                        artist("sk-a-bcq-5", "Briliant Corners Quartet")),
                new RunStats(),
                SEEN);
        assertThat(artistId("SKIDDLE", "sk-a-bcq-5")).isEqualTo(quartet);

        // Same name, but a different MusicBrainz id: two acts that share a name → a new artist. (No genre and a
        // venue the look-alike "Quartets" never played, so rule 4 has nothing to corroborate either.)
        UUID otherMbid = UUID.fromString("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");
        RunStats namesake = new RunStats();
        resolver.ingest(
                gig(
                        "SKIDDLE",
                        "sk-artists-7",
                        "BCQ",
                        "2026-12-07T20:00:00Z",
                        elsewhere,
                        new SourceArtist("sk-a-bcq-7", "The Brilliant Corners Quartet", otherMbid, List.of(), null)),
                namesake,
                SEEN);
        assertThat(artistId("SKIDDLE", "sk-a-bcq-7")).isNotEqualTo(quartet);
        assertThat(namesake.artists.created).isEqualTo(1);

        // A tribute act: similar name, same genre, same venue — but below 0.85, so it stays separate.
        RunStats tribute = new RunStats();
        resolver.ingest(
                gig(
                        "SKIDDLE",
                        "sk-artists-6",
                        "Oasish",
                        "2026-12-06T20:00:00Z",
                        venue,
                        artist("sk-a-oasish", "Oasish", "Rock")),
                tribute,
                SEEN);
        assertThat(artistId("SKIDDLE", "sk-a-oasish")).isNotEqualTo(oasis);
        assertThat(tribute.artists.created).isEqualTo(1);

        // The MBID and genres were stored for the created artist.
        assertThat(dsl.select(ARTIST.MBID)
                        .from(ARTIST)
                        .where(ARTIST.ID.eq(quartet))
                        .fetchOne(ARTIST.MBID))
                .isEqualTo(mbid);
        assertThat(dsl.fetchValue(
                        "select g.slug from catalog.artist_genre ag join catalog.genre g on g.id = ag.genre_id where ag.artist_id = ?",
                        quartet))
                .isEqualTo("jazz");
    }

    @Test
    void venuesMatchByDistanceAndNameOrByPostcodeAndNameOtherwiseTheyAreNew() {
        SourceVenue brass = venue("tm-v-brass", "The Brass Lantern", "NG1 5FB", 52.9548, -1.1581);
        resolver.ingest(
                gig(
                        "TICKETMASTER",
                        "tm-venues-1",
                        "Lost Sirens",
                        "2026-11-20T19:30:00Z",
                        brass,
                        artist("tm-a-sirens", "Lost Sirens")),
                new RunStats(),
                SEEN);
        UUID brassId = venueId("TICKETMASTER", "tm-v-brass");

        // (2) ~60 m away with a similar name.
        resolver.ingest(
                gig(
                        "SKIDDLE",
                        "sk-venues-1",
                        "Lost Sirens",
                        "2026-12-10T19:30:00Z",
                        venue("sk-v-brass", "Brass Lantern Club", null, 52.9553, -1.1584),
                        artist("sk-a-sirens", "Lost Sirens")),
                new RunStats(),
                SEEN);
        assertThat(venueId("SKIDDLE", "sk-v-brass")).isEqualTo(brassId);

        // (2) next door (~60 m) but a different name → a different venue.
        RunStats neighbour = new RunStats();
        resolver.ingest(
                gig(
                        "SKIDDLE",
                        "sk-venues-2",
                        "Lost Sirens",
                        "2026-12-11T19:30:00Z",
                        venue("sk-v-neighbour", "Rescue Rooms", "NG1 5FC", 52.9553, -1.1584),
                        artist("sk-a-sirens", "Lost Sirens")),
                neighbour,
                SEEN);
        assertThat(venueId("SKIDDLE", "sk-v-neighbour")).isNotEqualTo(brassId);
        assertThat(neighbour.venues.created).isEqualTo(1);

        // (2) the same name 2 km away is not within 150 m, and the postcode differs → a different venue.
        resolver.ingest(
                gig(
                        "SKIDDLE",
                        "sk-venues-3",
                        "Lost Sirens",
                        "2026-12-12T19:30:00Z",
                        venue("sk-v-far", "The Brass Lantern", "NG7 2RD", 52.9400, -1.1800),
                        artist("sk-a-sirens", "Lost Sirens")),
                new RunStats(),
                SEEN);
        assertThat(venueId("SKIDDLE", "sk-v-far")).isNotEqualTo(brassId);

        // (3) no coordinates, same postcode (formatted differently) and a loosely similar name. A fresh
        // venue is used: Skiddle already knows the Brass Lantern under another id (see the last case).
        resolver.ingest(
                gig(
                        "TICKETMASTER",
                        "tm-venues-4",
                        "Lost Sirens",
                        "2026-11-21T19:30:00Z",
                        venue("tm-v-glass", "The Glass Institute", "NG1 6HL", 52.9561, -1.1502),
                        artist("tm-a-sirens", "Lost Sirens")),
                new RunStats(),
                SEEN);
        resolver.ingest(
                gig(
                        "SKIDDLE",
                        "sk-venues-4",
                        "Lost Sirens",
                        "2026-12-13T19:30:00Z",
                        venue("sk-v-postcode", "Glass Institute Live Room", "ng16hl", null, null),
                        artist("sk-a-sirens", "Lost Sirens")),
                new RunStats(),
                SEEN);
        assertThat(venueId("SKIDDLE", "sk-v-postcode")).isEqualTo(venueId("TICKETMASTER", "tm-v-glass"));

        // The same source listing a second room in the same building under its own id: not merged,
        // because a source's own venue ids are authoritative within that source.
        RunStats secondRoom = new RunStats();
        resolver.ingest(
                gig(
                        "TICKETMASTER",
                        "tm-venues-5",
                        "Lost Sirens",
                        "2026-12-14T19:30:00Z",
                        venue("tm-v-brass-2", "The Brass Lantern 2", "NG1 5FB", 52.9548, -1.1581),
                        artist("tm-a-sirens", "Lost Sirens")),
                secondRoom,
                SEEN);
        assertThat(venueId("TICKETMASTER", "tm-v-brass-2")).isNotEqualTo(brassId);
        assertThat(secondRoom.venues.created).isEqualTo(1);

        assertThat(dsl.select(VENUE.NAME_NORM)
                        .from(VENUE)
                        .where(VENUE.ID.eq(brassId))
                        .fetchOne(VENUE.NAME_NORM))
                .isEqualTo("brass lantern");
    }

    private List<SourceGig> fixtureGigs() {
        return fixtures.pages(Instant.EPOCH, Instant.EPOCH, new CallBudget(0))
                .flatMap(p -> p.gigs().stream())
                .toList();
    }

    private UUID gigId(String source, String externalId) {
        return dsl.select(GIG_SOURCE.GIG_ID)
                .from(GIG_SOURCE)
                .where(GIG_SOURCE.SOURCE.eq(source))
                .and(GIG_SOURCE.EXTERNAL_ID.eq(externalId))
                .fetchSingle(GIG_SOURCE.GIG_ID);
    }

    private UUID artistId(String source, String externalId) {
        return dsl.select(ARTIST_EXTERNAL_ID.ARTIST_ID)
                .from(ARTIST_EXTERNAL_ID)
                .where(ARTIST_EXTERNAL_ID.SOURCE.eq(source))
                .and(ARTIST_EXTERNAL_ID.EXTERNAL_ID.eq(externalId))
                .fetchSingle(ARTIST_EXTERNAL_ID.ARTIST_ID);
    }

    private UUID venueId(String source, String externalId) {
        return dsl.select(VENUE_EXTERNAL_ID.VENUE_ID)
                .from(VENUE_EXTERNAL_ID)
                .where(VENUE_EXTERNAL_ID.SOURCE.eq(source))
                .and(VENUE_EXTERNAL_ID.EXTERNAL_ID.eq(externalId))
                .fetchSingle(VENUE_EXTERNAL_ID.VENUE_ID);
    }

    private static SourceGig gig(
            String source,
            String externalId,
            String title,
            String startsAt,
            SourceVenue venue,
            SourceArtist... artists) {
        return new SourceGig(
                source,
                externalId,
                "https://tickets.example.com/" + externalId,
                title,
                Instant.parse(startsAt),
                null,
                "SCHEDULED",
                "https://tickets.example.com/" + externalId,
                null,
                null,
                null,
                null,
                venue,
                List.of(artists));
    }

    private static SourceVenue venue(String externalId, String name, String postcode, Double lat, Double lng) {
        return new SourceVenue(externalId, name, null, null, postcode, lat, lng, "Europe/London");
    }

    private static SourceArtist artist(String externalId, String name, String... genres) {
        return new SourceArtist(externalId, name, null, List.of(genres), null);
    }
}
