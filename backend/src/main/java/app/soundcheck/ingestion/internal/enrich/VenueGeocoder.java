package app.soundcheck.ingestion.internal.enrich;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.catalog.City;
import app.soundcheck.catalog.GeoPoint;
import app.soundcheck.catalog.ReferenceData;
import app.soundcheck.catalog.VenueToLocate;
import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.source.SourceException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every venue gets coordinates: Ticketmaster sends some venues without any (63
 * venues and 197 upcoming gigs on 2025-07-04), and a venue no list can place is invisible to anyone
 * searching near home. After each run, venues still without coordinates are located by their postcode
 * ({@link PostcodeLookup}, up to 100 a call), else placed at their town's centre (the cities reference).
 * The first run back-fills; later runs find the few new ones.
 *
 * <p>A postcode that could not be looked up because the service failed is left for the next run, not
 * given its town's centre: coordinates, once set, are never overwritten. Northern Ireland postcodes (BT…)
 * are not looked up (their data is for non-commercial use only) and take their town's centre.
 */
@Component
public class VenueGeocoder {

    /** A full UK postcode, without spaces, upper case: outward code (2–4) + inward code (3). */
    static final Pattern POSTCODE = Pattern.compile("^[A-Z]{1,2}[0-9][A-Z0-9]?[0-9][A-Z]{2}$");

    private static final Logger log = LoggerFactory.getLogger(VenueGeocoder.class);

    /** What one pass did, stored in the run's stats. */
    public record Result(int venues, int byPostcode, int byTown, int left, int calls) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("venues", venues);
            map.put("byPostcode", byPostcode);
            map.put("byTown", byTown);
            map.put("left", left);
            map.put("calls", calls);
            return map;
        }
    }

    private final CatalogIngest catalog;
    private final ReferenceData reference;
    private final PostcodeLookup postcodes;
    private final IngestionProperties.Geocoding config;
    private final Clock clock;

    public VenueGeocoder(
            CatalogIngest catalog,
            ReferenceData reference,
            PostcodeLookup postcodes,
            IngestionProperties properties,
            Clock clock) {
        this.catalog = catalog;
        this.reference = reference;
        this.postcodes = postcodes;
        this.config = properties.geocoding();
        this.clock = clock;
    }

    public Result locateVenues() {
        List<VenueToLocate> venues = catalog.venuesWithoutLocation(Instant.now(clock), config.maxVenuesPerRun());
        Map<String, String> codeOf = new HashMap<>(); // venue id → postcode as sent
        List<String> toLookUp = new ArrayList<>();
        for (VenueToLocate venue : venues) {
            String code = lookupable(venue.postcode());
            if (code != null) {
                codeOf.put(venue.id().toString(), code);
                if (!toLookUp.contains(code)) {
                    toLookUp.add(code);
                }
            }
        }
        Map<String, GeoPoint> found = new HashMap<>();
        boolean lookupFailed = false;
        int calls = 0;
        for (int from = 0; from < toLookUp.size(); from += PostcodeLookup.BATCH) {
            List<String> batch = toLookUp.subList(from, Math.min(toLookUp.size(), from + PostcodeLookup.BATCH));
            try {
                found.putAll(postcodes.locate(batch));
                calls++;
            } catch (SourceException e) {
                log.warn("Postcode lookup stopped after {} call(s): {}", calls, e.getMessage());
                lookupFailed = true;
                break;
            }
        }
        int byPostcode = 0;
        int byTown = 0;
        int left = 0;
        for (VenueToLocate venue : venues) {
            String code = codeOf.get(venue.id().toString());
            GeoPoint point = code == null ? null : found.get(code);
            if (point != null) {
                catalog.completeVenue(venue.id(), point.lat(), point.lng());
                byPostcode++;
                continue;
            }
            if (code != null && lookupFailed) {
                left++; // not looked up: the next run tries the postcode again
                continue;
            }
            Optional<City> town = town(venue.city());
            if (town.isPresent()) {
                catalog.completeVenue(venue.id(), town.get().lat(), town.get().lng());
                byTown++;
            } else {
                left++;
            }
        }
        return new Result(venues.size(), byPostcode, byTown, left, calls);
    }

    /** The postcode in postcodes.io's form ("SG14 1AL"), or null when it is not one we may look up. */
    static @Nullable String lookupable(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String compact = raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!POSTCODE.matcher(compact).matches() || compact.startsWith("BT")) {
            return null;
        }
        return compact.substring(0, compact.length() - 3) + " " + compact.substring(compact.length() - 3);
    }

    /** The cities reference's place of that exact name (the most populous one when several share it). */
    private Optional<City> town(@Nullable String city) {
        if (city == null || city.isBlank()) {
            return Optional.empty();
        }
        String wanted = city.strip();
        return reference.searchCities(wanted, 10).stream()
                .filter(c -> c.name().equalsIgnoreCase(wanted))
                .findFirst();
    }
}
