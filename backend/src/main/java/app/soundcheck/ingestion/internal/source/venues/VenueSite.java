package app.soundcheck.ingestion.internal.source.venues;

import app.soundcheck.ingestion.internal.source.TimePrecision;
import org.jspecify.annotations.Nullable;

/**
 * One venue whose own website is read, from the registry ({@code venues/london.json}). The venue
 * is pinned: every listing lands on it, never on a fuzzy match. {@code ticketmasterVenueId} links it to
 * the venue Ticketmaster already created, so both sources share one catalog venue.
 *
 * @param adapter which reader understands the site ({@link VenueAdapter#kind()})
 * @param precision what the site's times mean: usually doors
 */
public record VenueSite(
        String key,
        String adapter,
        String url,
        Place venue,
        @Nullable String ticketmasterVenueId,
        TimePrecision precision,
        @Nullable String notes) {

    /** Where the venue is: from its own site, coordinates from postcodes.io. */
    public record Place(String name, String address, String city, String postcode, double lat, double lng) {}
}
