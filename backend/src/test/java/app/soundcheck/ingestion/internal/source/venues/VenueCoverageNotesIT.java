package app.soundcheck.ingestion.internal.source.venues;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.catalog.VenueDraft;
import app.soundcheck.catalog.VenueNote;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Coverage notes against real PostGIS: the "we don't read this venue's own listings" note lands on the venue it
 * names (by name or alias, in London, or at its postcode district for a common name), never on a venue
 * of the same name elsewhere, shows on the venue page and on every gig's venue, and goes when the venue
 * leaves the list.
 */
class VenueCoverageNotesIT extends IntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    CatalogIngest catalog;

    @Autowired
    VenueCoverageNotes notes;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ApplicationEventPublisher events;

    TestApi api;
    TestData data;
    TestApi.User fan;
    String tag;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalog, dsl, tx, events);
        fan = api.register("noted");
        tag = UUID.randomUUID().toString().substring(0, 6);
    }

    UUID venue(String name, String city, String postcode, Double lat, Double lng) {
        return tx.execute(
                status -> catalog.createVenue(new VenueDraft(name, null, city, postcode, lat, lng, "Europe/London")));
    }

    String noteOf(UUID venueId) {
        return ok(api.get("/api/v1/venues/" + venueId, fan))
                .path("coverageNote")
                .asText(null);
    }

    @Test
    void aNoteLandsOnTheNamedVenueOnlyAndShowsWhereverItAppears() {
        UUID ratsLondon = venue("The Bilge Rats " + tag, "London", "WC1X 8BZ", 51.5308, -0.1206);
        UUID ratsLeeds = venue("The Bilge Rats " + tag, "Leeds", "LS1 1AA", 53.7997, -1.5492);
        UUID aliasNoLocation = venue("Bilge " + tag, "London", null, null, null);
        UUID moonPutney = venue("The Pale Moon " + tag, "London", "SW15 1EU", 51.4659, -0.2161);
        UUID moonHerneHill = venue("The Pale Moon " + tag, "London", "SE24 9HU", 51.4530, -0.1018);

        int noted = catalog.applyCoverageNotes(List.of(
                new VenueNote(
                        "The Bilge Rats " + tag, List.of("Bilge " + tag), null, VenueCoverageNotes.note("because")),
                new VenueNote(
                        "The Pale Moon " + tag, List.of(), "SW15", VenueCoverageNotes.note("its list is elsewhere"))));

        assertThat(noted).isEqualTo(3);
        assertThat(noteOf(ratsLondon))
                .isEqualTo("We don't read this venue's own listings: because. Its gigs here come from Ticketmaster"
                        + " and fans, so some may be missing.");
        assertThat(noteOf(aliasNoLocation)).isNotNull(); // no coordinates yet, but its city is London
        assertThat(noteOf(moonPutney)).contains("its list is elsewhere");
        assertThat(noteOf(ratsLeeds)).isNull(); // the same name 270 km away
        assertThat(noteOf(moonHerneHill)).isNull(); // the same name, another postcode district

        // A gig at the venue carries the note in its summary (lists, gig page).
        UUID artist = data.artist("Noted Band " + tag);
        UUID gig = data.gig("Noted Band " + tag, ratsLondon, Instant.now().plus(10, ChronoUnit.DAYS), artist);
        assertThat(ok(api.get("/api/v1/gigs/" + gig, fan))
                        .path("gig")
                        .path("venue")
                        .path("coverageNote")
                        .asText(null))
                .startsWith("We don't read this venue's own listings");

        // A venue that leaves the list loses its note.
        catalog.applyCoverageNotes(
                List.of(new VenueNote("The Pale Moon " + tag, List.of(), "SW15", VenueCoverageNotes.note("still"))));
        assertThat(noteOf(ratsLondon)).isNull();
        assertThat(noteOf(moonPutney)).contains("still");
    }

    @Test
    void theShippedListNotesAVenueTheCatalogHas() {
        UUID ratsInCatalog = venue("The Water Rats", "London", "WC1X 8BZ", 51.5308, -0.1206);

        notes.apply();

        assertThat(noteOf(ratsInCatalog)).contains("copied in from Facebook and Eventbrite");
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as(String.valueOf(response.getBody()))
                .isTrue();
        return response.getBody();
    }
}
