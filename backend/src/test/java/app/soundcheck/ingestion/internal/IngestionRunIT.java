package app.soundcheck.ingestion.internal;

import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_SOURCE;
import static app.soundcheck.shared.jooq.ingestion.Tables.INGESTION_RUN;
import static app.soundcheck.shared.jooq.ingestion.Tables.RAW_PAYLOAD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.ingestion.GigsIngested;
import app.soundcheck.ingestion.internal.enrich.ArtistEnricher;
import app.soundcheck.ingestion.internal.enrich.VenueGeocoder;
import app.soundcheck.ingestion.internal.resolve.EntityResolver;
import app.soundcheck.ingestion.internal.source.CallBudget;
import app.soundcheck.ingestion.internal.source.GigSource;
import app.soundcheck.ingestion.internal.source.SourceArtist;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourcePage;
import app.soundcheck.ingestion.internal.source.SourceVenue;
import app.soundcheck.ingestion.internal.source.TimePrecision;
import app.soundcheck.ingestion.internal.source.TransientSourceException;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.MutableClock;
import app.soundcheck.support.TestApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * A run leaves an {@code ingestion_run} row with SUCCEEDED and counts (plus raw
 * payloads, an issues sample and the {@code GigsIngested} event); a second trigger is rejected while
 * one is RUNNING; old payloads are pruned.
 */
@RecordApplicationEvents
class IngestionRunIT extends IntegrationTest {

    @Autowired
    IngestionService service;

    @Autowired
    IngestionRuns runs;

    @Autowired
    EntityResolver resolver;

    @Autowired
    ArtistEnricher enricher;

    @Autowired
    VenueGeocoder geocoder;

    @Autowired
    CatalogIngest catalog;

    @Autowired
    IngestionGuard guard;

    @Autowired
    IngestionProperties properties;

    @Autowired
    MeterRegistry meters;

    @Autowired
    DSLContext dsl;

    @Autowired
    JsonMapper json;

    @Autowired
    MutableClock clock;

    @Autowired
    ApplicationEvents events;

    @Autowired
    TestRestTemplate rest;

    @AfterEach
    void backToRealTime() {
        clock.reset();
    }

    @Test
    void aRunRecordsStatusCountsPayloadsAndIssues() throws Exception {
        IngestionService.RunResult first = service.run("TICKETMASTER", "TEST");
        IngestionService.RunResult second = service.run("TICKETMASTER", "TEST");

        assertThat(first.status()).isEqualTo("SUCCEEDED");
        assertThat(second.status()).isEqualTo("SUCCEEDED");

        var row = dsl.selectFrom(INGESTION_RUN)
                .where(INGESTION_RUN.ID.eq(second.runId()))
                .fetchSingle();
        assertThat(row.getSource()).isEqualTo("TICKETMASTER");
        assertThat(row.getTrigger()).isEqualTo("TEST");
        assertThat(row.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(row.getFinishedAt()).isAfterOrEqualTo(row.getStartedAt());
        assertThat(row.getError()).isNull();

        // 240 fixture events: 233 gigs + 7 skipped (6 without a line-up, 1 without a date).
        JsonNode stats = json.readTree(row.getStats().data());
        assertThat(stats.path("fetched").asInt()).isEqualTo(240);
        assertThat(stats.path("skipped").asInt()).isEqualTo(7);
        assertThat(stats.path("unchanged").asInt()).isEqualTo(233); // the first run did the creating
        assertThat(stats.path("created").asInt()).isZero();
        assertThat(stats.path("errors").asInt()).isZero();
        assertThat(stats.path("pages").asInt()).isEqualTo(2);
        assertThat(stats.path("apiCalls").asInt()).isZero(); // fixtures make no calls
        assertThat(stats.path("gigs").path("matchedByExternalId").asInt()).isEqualTo(233);
        assertThat(stats.path("issueCounts").path("NO_ARTISTS").asInt()).isEqualTo(6);
        assertThat(stats.path("issueCounts").path("NO_DATE").asInt()).isEqualTo(1);
        assertThat(stats.path("issueCounts").path("MISSING_COORDINATES").asInt())
                .isPositive();
        assertThat(stats.path("enrichment").has("artists")).isTrue();
        assertThat(stats.path("geocoding").has("venues")).isTrue(); // every run locates what it can

        // Across the two runs every fixture gig was created or merged exactly once.
        @SuppressWarnings("unchecked")
        Map<String, Object> firstStats = first.stats();
        assertThat((int) firstStats.get("created") + (int) firstStats.get("merged") + (int) firstStats.get("unchanged"))
                .isEqualTo(233);

        JsonNode issues = json.readTree(row.getIssues().data());
        assertThat(issues.isArray()).isTrue();
        assertThat(issues.size()).isBetween(1, RunStats.MAX_ISSUES);
        assertThat(issues.path(0).has("kind")).isTrue();

        // One raw payload per fixture page for this run (enrichment payloads belong to the first run).
        assertThat(dsl.fetchCount(
                        RAW_PAYLOAD, RAW_PAYLOAD.RUN_ID.eq(second.runId()).and(RAW_PAYLOAD.SOURCE.eq("TICKETMASTER"))))
                .isEqualTo(2);
        assertThat(dsl.select(RAW_PAYLOAD.REQUEST)
                        .from(RAW_PAYLOAD)
                        .where(RAW_PAYLOAD.RUN_ID.eq(second.runId()))
                        .fetch(RAW_PAYLOAD.REQUEST))
                .contains("fixture:page-000.json", "fixture:page-001.json");

        assertThat(events.stream(GigsIngested.class)).anySatisfy(event -> {
            assertThat(event.runId()).isEqualTo(second.runId());
            assertThat(event.status()).isEqualTo("SUCCEEDED");
        });
    }

    @Test
    void aSecondTriggerIsRejectedWhileOneIsRunningAndASilentRunIsClosedAsAbandoned() {
        String source = "TICKETMASTER";
        UUID running = runs.start(source, "TEST", Instant.now(clock));
        try {
            assertThatThrownBy(() -> service.run(source, "TEST")).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.code()).isEqualTo("INGESTION_ALREADY_RUNNING");
                assertThat(e.status().value()).isEqualTo(409);
            });
            // No second row was opened.
            assertThat(dsl.fetchCount(
                            INGESTION_RUN, INGESTION_RUN.SOURCE.eq(source).and(INGESTION_RUN.STATUS.eq("RUNNING"))))
                    .isEqualTo(1);

            // A live run beats its heartbeat every 30 seconds. Eleven minutes of silence means its process
            // died: the row is closed as abandoned and a new run starts.
            clock.advance(Duration.ofMinutes(11));
            assertThat(service.run(source, "TEST").status()).isEqualTo("SUCCEEDED");
            var abandoned = dsl.selectFrom(INGESTION_RUN)
                    .where(INGESTION_RUN.ID.eq(running))
                    .fetchSingle();
            assertThat(abandoned.getStatus()).isEqualTo("FAILED");
            assertThat(abandoned.getError()).startsWith("abandoned");
        } finally {
            dsl.update(INGESTION_RUN)
                    .set(INGESTION_RUN.STATUS, "FAILED")
                    .where(INGESTION_RUN.ID.eq(running))
                    .and(INGESTION_RUN.STATUS.eq("RUNNING"))
                    .execute();
        }
    }

    /** A source for run-level tests: yields the given pages, then does what {@code afterwards} says. */
    private IngestionService serviceWith(
            String name, List<SourcePage> pages, java.util.function.Consumer<CallBudget> afterwards) {
        GigSource stub = new GigSource() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Stream<SourcePage> pages(Instant from, Instant to, CallBudget budget) {
                return Stream.concat(
                                pages.stream(), Stream.of((SourcePage) null).peek(x -> afterwards.accept(budget)))
                        .takeWhile(java.util.Objects::nonNull);
            }
        };
        return new IngestionService(
                List.of(stub), runs, resolver, enricher, geocoder, catalog, guard, properties, meters, clock);
    }

    private SourcePage page(String source, String key, SourceGig... gigs) {
        return new SourcePage("stub:" + key, json.createObjectNode().put("stub", key), List.of(gigs), List.of());
    }

    private SourceGig stubGig(String source, String id, String status) {
        return new SourceGig(
                source,
                id,
                "https://tickets.example.com/" + id,
                "Stub " + id,
                Instant.now(clock).plus(Duration.ofDays(9)),
                null,
                status,
                "https://tickets.example.com/" + id,
                null,
                null,
                null,
                null,
                new SourceVenue("v-" + id, "Stub Venue " + id, null, "Testville", null, 58.1, -6.4, "Europe/London"),
                List.of(new SourceArtist("a-" + id, "Stub Act " + id, null, List.of("Rock"), null)));
    }

    @Test
    void aSpentCallBudgetEndsTheRunAsPartialAndKeepsWhatWasIngested() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        // SKIDDLE is used as the stub's name: artist_external_id only accepts the known source names.
        IngestionService partial = serviceWith(
                "SKIDDLE",
                List.of(page("SKIDDLE", "p1", stubGig("SKIDDLE", "partial-" + tag, "SCHEDULED"))),
                budget -> {
                    for (int i = 0; i < properties.callBudget(); i++) {
                        budget.record();
                    }
                    assertThat(budget.allowsAnotherCall()).isFalse(); // the source wanted one more call
                });

        IngestionService.RunResult result = partial.run("SKIDDLE", "TEST");

        assertThat(result.status()).isEqualTo("PARTIAL");
        var row = dsl.selectFrom(INGESTION_RUN)
                .where(INGESTION_RUN.ID.eq(result.runId()))
                .fetchSingle();
        assertThat(row.getStatus()).isEqualTo("PARTIAL");
        assertThat(row.getFinishedAt()).isNotNull();
        JsonNode stats = json.readTree(row.getStats().data());
        assertThat(stats.path("created").asInt()).isEqualTo(1);
        assertThat(stats.path("apiCalls").asInt()).isEqualTo(properties.callBudget());
        assertThat(stats.path("issueCounts").path("BUDGET_EXHAUSTED").asInt()).isEqualTo(1);
        // A partial run has not seen everything, so it must not flag gigs as "no longer listed".
        assertThat(stats.path("issueCounts").has("NOT_SEEN")).isFalse();
        assertThat(dsl.fetchValue("select count(*) from catalog.gig_source where external_id = ?", "partial-" + tag))
                .isEqualTo(1L);
    }

    @Test
    void aSourceThatGivesUpEndsTheRunAsFailedAndABadGigRollsBackAloneWithoutInflatingTheCounts() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        SourceGig good = stubGig("SKIDDLE", "good-" + tag, "SCHEDULED");
        SourceGig bad = stubGig("SKIDDLE", "bad-" + tag, "NOT_A_STATUS"); // violates the gig status check constraint
        IngestionService failing = serviceWith("SKIDDLE", List.of(page("SKIDDLE", "p1", good, bad)), budget -> {
            throw new TransientSourceException("Skiddle answered 503 for /events (after retries)");
        });

        IngestionService.RunResult result = failing.run("SKIDDLE", "TEST");

        assertThat(result.status()).isEqualTo("FAILED");
        var row = dsl.selectFrom(INGESTION_RUN)
                .where(INGESTION_RUN.ID.eq(result.runId()))
                .fetchSingle();
        assertThat(row.getStatus()).isEqualTo("FAILED"); // closed, not left RUNNING
        assertThat(row.getError()).contains("TransientSourceException").contains("503");
        JsonNode stats = json.readTree(row.getStats().data());
        assertThat(stats.path("fetched").asInt()).isEqualTo(2);
        assertThat(stats.path("created").asInt()).isEqualTo(1);
        assertThat(stats.path("errors").asInt()).isEqualTo(1);
        assertThat(stats.path("issueCounts").path("INGEST_ERROR").asInt()).isEqualTo(1);
        // The bad gig's venue and artist were rolled back with it: neither stored nor counted.
        assertThat(stats.path("venues").path("created").asInt()).isEqualTo(1);
        assertThat(stats.path("artists").path("created").asInt()).isEqualTo(1);
        assertThat(dsl.fetchValue(
                        "select count(*) from catalog.venue_external_id where external_id = ?", "v-bad-" + tag))
                .isEqualTo(0L);
        assertThat(dsl.fetchValue("select count(*) from catalog.gig_source where external_id = ?", "good-" + tag))
                .isEqualTo(1L);
        // A new run can start at once.
        assertThat(serviceWith("SKIDDLE", List.of(), budget -> {})
                        .run("SKIDDLE", "TEST")
                        .status())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void aGigOnlyAVenueSiteListedIsUnlistedAfterTwoFullReadsWithoutItAndComesBackWhenListedAgain() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String site = "lantern-" + tag;
        Instant base = Instant.now(clock);
        SourceGig a = venueGig(site, "a", "Harbour Hounds " + tag, base.plus(Duration.ofDays(9)));
        SourceGig b = venueGig(site, "b", "Night Moths " + tag, base.plus(Duration.ofDays(10)));
        SourceGig c = venueGig(site, "c", "Pale Kites " + tag, base.plus(Duration.ofDays(11)));

        assertThat(venueRun(fullRead(site, a, b, c))).isEqualTo("SUCCEEDED");
        UUID gigB = gigOf(site + ":b");
        UUID gigC = gigOf(site + ":c");
        // A fan added C as well: a gig someone else vouches for is never unlisted by the site's silence.
        dsl.insertInto(GIG_SOURCE)
                .set(GIG_SOURCE.SOURCE, "USER")
                .set(GIG_SOURCE.EXTERNAL_ID, "fan-" + tag)
                .set(GIG_SOURCE.GIG_ID, gigC)
                .set(GIG_SOURCE.LAST_SEEN_AT, base.atOffset(java.time.ZoneOffset.UTC))
                .set(GIG_SOURCE.PRIMARY_LISTING, false)
                .execute();

        // The site fails one day: not a read in full, so it says nothing about B.
        clock.advance(Duration.ofDays(1));
        venueRun(page("VENUE", site + "-failed"));
        // Two full reads without B or C.
        clock.advance(Duration.ofDays(1));
        venueRun(fullRead(site, a));
        assertThat(status(gigB)).as("one full read without it").isEqualTo("SCHEDULED");
        clock.advance(Duration.ofDays(1));
        venueRun(fullRead(site, a));

        assertThat(status(gigB)).isEqualTo("UNLISTED");
        assertThat(status(gigC)).isEqualTo("SCHEDULED");
        assertThat(status(gigOf(site + ":a"))).isEqualTo("SCHEDULED");
        // Gone from the lists that help people find gigs, here the venue's page.
        TestApi api = new TestApi(rest);
        TestApi.User fan = api.register("fan");
        UUID venueId = dsl.fetchValue(dsl.select(GIG.VENUE_ID).from(GIG).where(GIG.ID.eq(gigB)));
        JsonNode listed = api.get("/api/v1/venues/" + venueId + "/gigs", fan).getBody();
        assertThat(listed.path("items").findValuesAsText("id"))
                .contains(gigOf(site + ":a").toString(), gigC.toString())
                .doesNotContain(gigB.toString());

        // The site lists B again: it is back.
        clock.advance(Duration.ofDays(1));
        venueRun(fullRead(site, a, b));
        assertThat(status(gigB)).isEqualTo("SCHEDULED");
    }

    private String venueRun(SourcePage... pages) {
        return serviceWith("VENUE", List.of(pages), budget -> {})
                .run("VENUE", "TEST")
                .status();
    }

    /** One page of the site's listings, marked as completing a read of the whole site. */
    private SourcePage fullRead(String site, SourceGig... gigs) {
        return page("VENUE", site, gigs).completing(site);
    }

    private SourceGig venueGig(String site, String id, String title, Instant startsAt) {
        return new SourceGig(
                "VENUE",
                site + ":" + id,
                "https://" + site + ".example.com/events/" + id,
                title,
                startsAt,
                null,
                "SCHEDULED",
                null,
                null,
                null,
                null,
                null,
                new SourceVenue(site, "The Lantern " + site, null, "Testville", null, 57.3, -6.1, "Europe/London"),
                List.of(new SourceArtist("", title, null, List.of(), null)),
                TimePrecision.DOORS);
    }

    private UUID gigOf(String externalId) {
        return dsl.fetchValue(dsl.select(GIG_SOURCE.GIG_ID)
                .from(GIG_SOURCE)
                .where(GIG_SOURCE.SOURCE.eq("VENUE"))
                .and(GIG_SOURCE.EXTERNAL_ID.eq(externalId)));
    }

    private String status(UUID gigId) {
        return dsl.fetchValue(dsl.select(GIG.STATUS).from(GIG).where(GIG.ID.eq(gigId)));
    }

    @Test
    void anUnknownSourceIsA404AndOldPayloadsArePruned() {
        assertThatThrownBy(() -> service.run("NOPE", "TEST"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("SOURCE_UNKNOWN"));

        IngestionService.RunResult run = service.run("TICKETMASTER", "TEST");
        assertThat(dsl.fetchCount(RAW_PAYLOAD, RAW_PAYLOAD.RUN_ID.eq(run.runId())))
                .isPositive();

        // Kept 7 days. 6 days on, nothing of this run is old enough; 8 days on, everything is.
        assertThat(properties.payloadRetention()).isEqualTo(Duration.ofDays(7));
        Instant now = Instant.now(clock);
        runs.prunePayloads(now.plus(Duration.ofDays(6)).minus(properties.payloadRetention()));
        assertThat(dsl.fetchCount(RAW_PAYLOAD, RAW_PAYLOAD.RUN_ID.eq(run.runId())))
                .isPositive();
        runs.prunePayloads(now.plus(Duration.ofDays(8)).minus(properties.payloadRetention()));
        assertThat(dsl.fetchCount(RAW_PAYLOAD, RAW_PAYLOAD.RUN_ID.eq(run.runId())))
                .isZero();
    }
}
