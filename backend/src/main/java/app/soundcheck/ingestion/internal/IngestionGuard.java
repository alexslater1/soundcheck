package app.soundcheck.ingestion.internal;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.ingestion.internal.source.GigSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keeps invented and real listings apart in one database. The synthetic fixtures are stored
 * under the same source name as real Ticketmaster data, so switching {@code INGESTION_MODE} on a database
 * that already holds the other kind would leave made-up gigs next to real ones. The command-line and
 * scheduled triggers ask this guard first; {@code make purge-fixtures} removes the synthetic rows.
 */
@Component
class IngestionGuard {

    /** External ids of the synthetic fixtures' events start with this (see ops/scripts/gen_fixtures.py). */
    static final String SYNTHETIC_PREFIX = "G5vsyn";

    private final CatalogIngest catalog;
    private final boolean allowMixed;

    IngestionGuard(CatalogIngest catalog, @Value("${soundcheck.ingestion.allow-mixed-data:false}") boolean allowMixed) {
        this.catalog = catalog;
        this.allowMixed = allowMixed;
    }

    /** @throws IllegalStateException with what to do when running this source would mix the two kinds */
    void check(GigSource source) {
        if (allowMixed) {
            return;
        }
        long synthetic = catalog.countListings(source.name(), SYNTHETIC_PREFIX);
        long real = catalog.countListings(source.name(), null) - synthetic;
        if (source.synthetic() && real > 0) {
            throw new IllegalStateException("The catalog holds " + real + " real " + source.name()
                    + " listings; ingesting the synthetic fixtures would mix invented gigs with them."
                    + " Use INGESTION_MODE=live, or a fresh database (make reset), or set"
                    + " INGESTION_ALLOW_MIXED_DATA=true if that is what you want.");
        }
        if (!source.synthetic() && synthetic > 0) {
            throw new IllegalStateException("The catalog holds " + synthetic
                    + " synthetic fixture listings; a real ingestion would mix real gigs with them."
                    + " Run `make purge-fixtures` first, or set INGESTION_ALLOW_MIXED_DATA=true.");
        }
    }
}
