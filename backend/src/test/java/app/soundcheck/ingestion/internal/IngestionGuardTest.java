package app.soundcheck.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.ingestion.internal.source.CallBudget;
import app.soundcheck.ingestion.internal.source.GigSource;
import app.soundcheck.ingestion.internal.source.SourcePage;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The guard that keeps invented fixture gigs and real listings out of the same database. */
class IngestionGuardTest {

    private static GigSource source(boolean synthetic) {
        return new GigSource() {
            @Override
            public String name() {
                return "TICKETMASTER";
            }

            @Override
            public Stream<SourcePage> pages(Instant from, Instant to, CallBudget budget) {
                return Stream.empty();
            }

            @Override
            public boolean synthetic() {
                return synthetic;
            }
        };
    }

    private static CatalogIngest catalog(long total, long synthetic) {
        CatalogIngest catalog = mock(CatalogIngest.class);
        when(catalog.countListings("TICKETMASTER", null)).thenReturn(total);
        when(catalog.countListings("TICKETMASTER", IngestionGuard.SYNTHETIC_PREFIX))
                .thenReturn(synthetic);
        return catalog;
    }

    @Test
    void anEmptyCatalogAcceptsEitherKind() {
        assertThatCode(() -> new IngestionGuard(catalog(0, 0), false).check(source(true)))
                .doesNotThrowAnyException();
        assertThatCode(() -> new IngestionGuard(catalog(0, 0), false).check(source(false)))
                .doesNotThrowAnyException();
    }

    @Test
    void likeGoesWithLike() {
        assertThatCode(() -> new IngestionGuard(catalog(233, 233), false).check(source(true)))
                .doesNotThrowAnyException();
        assertThatCode(() -> new IngestionGuard(catalog(1164, 0), false).check(source(false)))
                .doesNotThrowAnyException();
    }

    @Test
    void syntheticFixturesAreRefusedWhenRealListingsExistAndTheOtherWayRound() {
        assertThatThrownBy(() -> new IngestionGuard(catalog(1164, 0), false).check(source(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1164 real");
        assertThatThrownBy(() -> new IngestionGuard(catalog(233, 233), false).check(source(false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("make purge-fixtures");
    }

    @Test
    void theOverrideAllowsMixing() {
        assertThatCode(() -> new IngestionGuard(catalog(1164, 0), true).check(source(true)))
                .doesNotThrowAnyException();
    }
}
