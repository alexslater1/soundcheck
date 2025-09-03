package app.soundcheck.ingestion.internal.source.setlistfm;

import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_SOURCE;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE_EXTERNAL_ID;
import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.ingestion.internal.RunStats;
import app.soundcheck.ingestion.internal.resolve.EntityResolver;
import app.soundcheck.ingestion.internal.source.SourceArtist;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceVenue;
import app.soundcheck.ingestion.internal.source.TimePrecision;
import app.soundcheck.support.IntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The way back: {@code make purge-past-gigs} removes the gigs only setlist.fm lists and their venues,
 * and keeps a gig another source lists (with its setlist.fm link).
 */
class PurgeSetlistFmBackfillIT extends IntegrationTest {

    static final Instant SEEN = Instant.parse("2026-10-04T05:10:00Z");

    @Autowired
    EntityResolver resolver;

    @Autowired
    SetlistFmSyncs syncs;

    @Autowired
    DSLContext dsl;

    @Test
    void removesGigsOnlySetlistFmListsAndKeepsGigsOtherSourcesList() throws IOException {
        // Only setlist.fm knows this one, at a venue only setlist.fm knows.
        resolver.ingest(setlist("purge-1", "v-purge-1", "Lonely Lantern Bar", "Hollow Pines"), new RunStats(), SEEN);
        // Ticketmaster lists this one; a setlist joined it.
        resolver.ingest(
                new SourceGig(
                        "TICKETMASTER",
                        "tm-purge-2",
                        null,
                        "Bright Ferry",
                        Instant.parse("2025-02-07T20:00:00Z"),
                        null,
                        "SCHEDULED",
                        null,
                        null,
                        null,
                        null,
                        null,
                        new SourceVenue(
                                "tm-v-purge-2",
                                "Ferry Hall",
                                null,
                                "Leeds",
                                "LS1 1AA",
                                53.7965,
                                -1.5478,
                                "Europe/London"),
                        List.of(new SourceArtist("tm-a-purge-2", "Bright Ferry", null, List.of(), null))),
                new RunStats(),
                SEEN);
        SourceGig joining = new SourceGig(
                "SETLISTFM",
                "purge-2",
                "https://www.setlist.fm/setlist/x/purge-2.html",
                "Bright Ferry",
                LocalDate.parse("2025-02-07")
                        .atTime(20, 0)
                        .atZone(ZoneId.of("Europe/London"))
                        .toInstant(),
                null,
                "SCHEDULED",
                null,
                null,
                null,
                null,
                null,
                new SourceVenue("v-purge-2", "Ferry Hall", null, "Leeds", null, 53.8008, -1.5491, "Europe/London"),
                List.of(new SourceArtist("", "Bright Ferry", null, List.of(), null)),
                TimePrecision.DATE_ONLY);
        resolver.ingest(joining, new RunStats(), SEEN);
        UUID kept = gigOf("TICKETMASTER", "tm-purge-2");
        assertThat(gigOf("SETLISTFM", "purge-2")).isEqualTo(kept);
        syncs.markSynced(UUID.randomUUID(), SEEN, 1);

        dsl.execute(Files.readString(Path.of("../ops/scripts/purge-setlistfm-backfill.sql")));

        assertThat(dsl.fetchCount(GIG_SOURCE, GIG_SOURCE.EXTERNAL_ID.eq("purge-1")))
                .isZero();
        assertThat(dsl.fetchCount(VENUE_EXTERNAL_ID, VENUE_EXTERNAL_ID.EXTERNAL_ID.eq("v-purge-1")))
                .isZero();
        assertThat(dsl.fetchCount(GIG, GIG.ID.eq(kept))).isOne();
        assertThat(dsl.fetchCount(GIG_SOURCE, GIG_SOURCE.EXTERNAL_ID.eq("purge-2")))
                .isOne();
        assertThat(syncs.syncedAt()).isEmpty();
    }

    private static SourceGig setlist(String id, String venueId, String venueName, String act) {
        return new SourceGig(
                "SETLISTFM",
                id,
                "https://www.setlist.fm/setlist/x/" + id + ".html",
                act,
                LocalDate.parse("2025-02-06")
                        .atTime(20, 0)
                        .atZone(ZoneId.of("Europe/London"))
                        .toInstant(),
                null,
                "SCHEDULED",
                null,
                null,
                null,
                null,
                null,
                new SourceVenue(venueId, venueName, null, "Leeds", null, 53.8008, -1.5491, "Europe/London"),
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
}
