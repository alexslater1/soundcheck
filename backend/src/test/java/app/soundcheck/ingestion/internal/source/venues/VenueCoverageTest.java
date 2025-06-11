package app.soundcheck.ingestion.internal.source.venues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.catalog.ListingOverlap;
import app.soundcheck.ingestion.internal.source.TimePrecision;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The keep-or-drop numbers: listings grouped by venue key, misses counted, the threshold applied. */
class VenueCoverageTest {

    static final Instant NOW = Instant.parse("2026-10-02T09:00:00Z");

    static VenueSite site(String key, String name, String ticketmasterId) {
        return new VenueSite(
                key,
                "squarespace",
                "https://" + key + ".example/",
                new VenueSite.Place(name, "1 Test St", "London", "N1 1AA", 51.5, -0.1),
                ticketmasterId,
                TimePrecision.DOORS,
                null);
    }

    static ListingOverlap listing(String id, String title, boolean onTicketmaster) {
        return new ListingOverlap(id, UUID.randomUUID(), title, NOW.plusSeconds(86_400), onTicketmaster);
    }

    @Test
    void countsWhatTicketmasterMissesPerVenueAndAppliesTheThreshold() {
        CatalogIngest catalog = mock(CatalogIngest.class);
        when(catalog.listingsAlsoListedBy(
                        org.mockito.ArgumentMatchers.eq("VENUE"),
                        org.mockito.ArgumentMatchers.eq("TICKETMASTER"),
                        any(),
                        any()))
                .thenReturn(List.of(
                        listing("dome:/e/1", "Pale Kites", true),
                        listing("dome:/e/2", "Night Bus", true),
                        listing("dome:/e/3", "Rope Burns", false),
                        listing("pub:/e/1", "Glass Owls", false),
                        listing("pub:/e/2", "Dune Sisters", false)));
        VenueRegistry registry = new VenueRegistry(List.of(
                site("dome", "The Dome", "KovZ1"), site("pub", "The Pub", null), site("quiet", "Quiet Bar", null)));

        List<VenueCoverage.Row> rows = new VenueCoverage(catalog, registry, Clock.fixed(NOW, ZoneOffset.UTC)).measure();

        assertThat(rows).extracting(VenueCoverage.Row::venueKey).containsExactly("dome", "pub", "quiet");
        VenueCoverage.Row dome = rows.get(0);
        assertThat(dome.listed()).isEqualTo(3);
        assertThat(dome.alsoOnTicketmaster()).isEqualTo(2);
        assertThat(dome.missedCount()).isEqualTo(1);
        assertThat(dome.earnsItsPlace()).isFalse(); // one extra show is not worth a scraper
        assertThat(rows.get(1).earnsItsPlace()).isTrue();
        assertThat(rows.get(2).listed()).isZero();

        String report = VenueCoverage.report(rows, NOW);
        assertThat(report)
                .contains("| The Dome | yes | 3 | 2 | 1 | drop? |")
                .contains("| The Pub | no | 2 | 0 | 2 | keep |")
                .contains("| Quiet Bar | no | 0 | 0 | 0 | nothing read |")
                .contains("Rope Burns"); // the Dome's miss is listed for a person to check
    }
}
