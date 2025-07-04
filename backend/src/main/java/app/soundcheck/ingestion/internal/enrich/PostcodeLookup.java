package app.soundcheck.ingestion.internal.enrich;

import app.soundcheck.catalog.GeoPoint;
import java.util.List;
import java.util.Map;

/** Coordinates for UK postcodes. */
public interface PostcodeLookup {

    /** The most postcodes one {@link #locate} call takes. */
    int BATCH = 100;

    /**
     * Where each of up to {@link #BATCH} postcodes is, keyed by the postcode as given; a postcode that is
     * not found (mistyped, retired) is absent from the result.
     */
    Map<String, GeoPoint> locate(List<String> postcodes);
}
