package app.soundcheck.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.soundcheck.ingestion.internal.resolve.EntityResolver;
import app.soundcheck.ingestion.internal.source.SourceArtist;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceVenue;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;

/**
 * A venue Ticketmaster sent without coordinates (Tramshed, Cardiff) left its gigs without a
 * location, so no list could show their distance, until an ingestion run located it. The scheduler
 * now locates such venues when it starts, too. Fixtures mode has no postcode lookup, so the venue lands
 * at its town's centre, as it would when postcodes.io does not know the postcode.
 */
class VenuesLocatedAtStartIT extends IntegrationTest {

    @Autowired
    IngestionSchedule schedule;

    @Autowired
    EntityResolver resolver;

    @Autowired
    DSLContext dsl;

    @Autowired
    TestRestTemplate rest;

    @Test
    void aVenueWithoutCoordinatesIsLocatedWhenTheSchedulerStartsAndItsGigsGetADistance() {
        TestApi api = new TestApi(rest);
        TestApi.User fan = api.register("cardiff");
        JsonNode london = api.get("/api/v1/cities?q=london", null).getBody().path(0);
        assertThat(api.patch(
                                "/api/v1/me",
                                Map.of("homeCityId", london.path("id").asInt()),
                                fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String externalId = "tm-tramshed-" + tag;
        resolver.ingest(
                new SourceGig(
                        "TICKETMASTER",
                        externalId,
                        null,
                        "Tramshed Night " + tag,
                        Instant.now().plus(Duration.ofDays(9)).truncatedTo(ChronoUnit.HOURS),
                        null,
                        "SCHEDULED",
                        null,
                        new BigDecimal("15.00"),
                        null,
                        "GBP",
                        null,
                        // As Ticketmaster sends it: an address and a postcode, no coordinates.
                        new SourceVenue(
                                "tm-v-tramshed-" + tag,
                                "Tramshed " + tag,
                                "Clare Road",
                                "Cardiff",
                                "CF11 6QP",
                                null,
                                null,
                                "Europe/London"),
                        List.of(new SourceArtist("tm-a-" + tag, "Harbour Lights " + tag, null, List.of("Rock"), null))),
                new RunStats(),
                Instant.now());
        UUID gigId = dsl.fetchOne(
                        "select gig_id from catalog.gig_source where source = 'TICKETMASTER' and external_id = ?",
                        externalId)
                .get(0, UUID.class);
        String path = "/api/v1/gigs/" + gigId;
        assertThat(api.get(path, fan).getBody().path("gig").path("distanceKm").isNull())
                .isTrue();

        schedule.locateVenues();

        JsonNode cardiff = api.get("/api/v1/cities?q=cardiff", null).getBody().path(0);
        JsonNode gig = api.get(path, fan).getBody().path("gig");
        assertThat(gig.path("venue").path("location").path("lat").asDouble())
                .isCloseTo(cardiff.path("lat").asDouble(), within(1e-6));
        // London's centre to Cardiff's is about 210 km as the crow flies.
        assertThat(gig.path("distanceKm").isNumber()).as(gig.toString()).isTrue();
        assertThat(gig.path("distanceKm").asDouble()).isBetween(190.0, 230.0);
    }

    @Test
    void thePassRunsWhenTheApplicationIsReady() throws NoSuchMethodException {
        EventListener listener = AnnotatedElementUtils.findMergedAnnotation(
                IngestionSchedule.class.getDeclaredMethod("locateVenues"), EventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.classes()).containsExactly(ApplicationReadyEvent.class);
    }
}
