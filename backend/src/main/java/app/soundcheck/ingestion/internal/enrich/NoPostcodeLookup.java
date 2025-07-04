package app.soundcheck.ingestion.internal.enrich;

import app.soundcheck.catalog.GeoPoint;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fixtures mode (offline runs and the tests): no postcode is looked up, so a venue without coordinates
 * is placed at its town's centre ({@link VenueGeocoder}).
 */
@Component
@ConditionalOnProperty(name = "soundcheck.ingestion.mode", havingValue = "fixtures", matchIfMissing = true)
public class NoPostcodeLookup implements PostcodeLookup {

    @Override
    public Map<String, GeoPoint> locate(List<String> postcodes) {
        return Map.of();
    }
}
