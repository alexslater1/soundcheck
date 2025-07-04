package app.soundcheck.ingestion.internal.enrich;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.catalog.City;
import app.soundcheck.catalog.GeoPoint;
import app.soundcheck.catalog.ReferenceData;
import app.soundcheck.catalog.VenueToLocate;
import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.source.TransientSourceException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Which venues get coordinates, from where, and what waits for the next run. */
class VenueGeocoderTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T05:10:00Z"), ZoneOffset.UTC);
    static final City HERTFORD = new City(1, "Hertford", "England", 51.7959, -0.0786, 26_000);
    static final City CARDIFF = new City(2, "Cardiff", "Wales", 51.4800, -3.1800, 335_000);

    CatalogIngest catalog = mock(CatalogIngest.class);
    ReferenceData reference = mock(ReferenceData.class);
    List<List<String>> calls = new ArrayList<>();

    VenueGeocoder geocoder(PostcodeLookup lookup) {
        when(reference.searchCities(eq("Hertford"), anyInt())).thenReturn(List.of(HERTFORD));
        when(reference.searchCities(eq("Cardiff"), anyInt())).thenReturn(List.of(CARDIFF));
        IngestionProperties properties = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("soundcheck.ingestion", IngestionProperties.class);
        return new VenueGeocoder(catalog, reference, lookup, properties, CLOCK);
    }

    PostcodeLookup knowing(Map<String, GeoPoint> places) {
        return postcodes -> {
            calls.add(List.copyOf(postcodes));
            return places.entrySet().stream()
                    .filter(e -> postcodes.contains(e.getKey()))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        };
    }

    static VenueToLocate venue(String name, String postcode, String city) {
        return new VenueToLocate(UUID.randomUUID(), name, postcode, city);
    }

    @Test
    void aVenueIsPlacedByItsPostcodeElseAtItsTownsCentreElseLeft() {
        VenueToLocate exchange = venue("Hertford Corn Exchange", "sg141al", "Hertford");
        VenueToLocate tramshed = venue("Tramshed", "CF11 6QP", "Cardiff"); // postcodes.io does not know it
        VenueToLocate belfast = venue("Belfast Barn", "BT1 1AA", "Hertford"); // never looked up
        VenueToLocate typo = venue("Typo Hall", "not a postcode", "Hertford");
        VenueToLocate nowhere = venue("Nowhere Club", null, "Atlantis");
        when(catalog.venuesWithoutLocation(any(), anyInt()))
                .thenReturn(List.of(exchange, tramshed, belfast, typo, nowhere));

        VenueGeocoder.Result result = geocoder(knowing(Map.of("SG14 1AL", new GeoPoint(51.7957, -0.0779))))
                .locateVenues();

        assertThat(calls).containsExactly(List.of("SG14 1AL", "CF11 6QP")); // one call; no BT postcode
        verify(catalog).completeVenue(exchange.id(), 51.7957, -0.0779);
        verify(catalog).completeVenue(tramshed.id(), CARDIFF.lat(), CARDIFF.lng());
        verify(catalog).completeVenue(belfast.id(), HERTFORD.lat(), HERTFORD.lng());
        verify(catalog).completeVenue(typo.id(), HERTFORD.lat(), HERTFORD.lng());
        verify(catalog, never()).completeVenue(eq(nowhere.id()), anyDouble(), anyDouble());
        assertThat(result).isEqualTo(new VenueGeocoder.Result(5, 1, 3, 1, 1));
    }

    @Test
    void whenTheLookupFailsAVenueWithAPostcodeWaitsForTheNextRunInsteadOfTakingItsTownsCentre() {
        VenueToLocate exchange = venue("Hertford Corn Exchange", "SG14 1AL", "Hertford");
        VenueToLocate noPostcode = venue("Market Hall", null, "Hertford");
        when(catalog.venuesWithoutLocation(any(), anyInt())).thenReturn(List.of(exchange, noPostcode));

        VenueGeocoder.Result result = geocoder(postcodes -> {
                    throw new TransientSourceException("postcodes.io answered 503 (after retries)");
                })
                .locateVenues();

        // Coordinates, once set, are never overwritten: a town's centre now would be for good.
        verify(catalog, never()).completeVenue(eq(exchange.id()), anyDouble(), anyDouble());
        verify(catalog).completeVenue(noPostcode.id(), HERTFORD.lat(), HERTFORD.lng());
        assertThat(result).isEqualTo(new VenueGeocoder.Result(2, 0, 1, 1, 0));
    }

    @Test
    void postcodesGoAHundredACallAndEachOnlyOnce() {
        List<VenueToLocate> venues = new ArrayList<>(IntStream.range(0, 150)
                .mapToObj(i -> venue("Venue " + i, String.format("SG%d 1AL", 1 + i % 99), "Hertford"))
                .toList());
        venues.addAll(IntStream.range(0, 60)
                .mapToObj(i -> venue("Venue b" + i, String.format("CF%d 6QP", 1 + i), "Cardiff"))
                .toList());
        when(catalog.venuesWithoutLocation(any(), anyInt())).thenReturn(venues);

        geocoder(knowing(Map.of())).locateVenues();

        // 99 distinct SG postcodes (150 venues) + 60 CF ones = 159 distinct: two calls, 100 and 59.
        assertThat(calls).extracting(List::size).containsExactly(100, 59);
    }

    @Test
    void postcodesAreSentInTheServicesFormAndOnlyWhenTheyAreReal() {
        assertThat(VenueGeocoder.lookupable(" PO40RD ")).isEqualTo("PO4 0RD");
        assertThat(VenueGeocoder.lookupable("sg141al")).isEqualTo("SG14 1AL");
        assertThat(VenueGeocoder.lookupable("E9 6NU")).isEqualTo("E9 6NU");
        assertThat(VenueGeocoder.lookupable("BT1 1AA")).isNull(); // Northern Ireland: non-commercial data only
        assertThat(VenueGeocoder.lookupable("N/A")).isNull();
        assertThat(VenueGeocoder.lookupable(null)).isNull();
    }
}
