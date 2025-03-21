package app.soundcheck.ingestion.internal;

import static app.soundcheck.shared.jooq.ingestion.Tables.INGESTION_RUN;
import static app.soundcheck.shared.jooq.ingestion.Tables.RAW_PAYLOAD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.ingestion.GigsIngested;
import app.soundcheck.ingestion.internal.enrich.ArtistEnricher;
import app.soundcheck.ingestion.internal.resolve.EntityResolver;
import app.soundcheck.ingestion.internal.source.CallBudget;
import app.soundcheck.ingestion.internal.source.GigSource;
import app.soundcheck.ingestion.internal.source.SourceArtist;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourcePage;
import app.soundcheck.ingestion.internal.source.SourceVenue;
import app.soundcheck.ingestion.internal.source.TransientSourceException;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.MutableClock;
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
    CatalogIngest catalog;

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
        return new IngestionService(List.of(stub), runs, resolver, enricher, catalog, properties, meters, clock);
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
    void anUnknownSourceIsA404AndOldPayloadsArePruned() {
        assertThatThrownBy(() -> service.run("NOPE", "TEST"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("SOURCE_UNKNOWN"));

        IngestionService.RunResult run = service.run("TICKETMASTER", "TEST");
        assertThat(dsl.fetchCount(RAW_PAYLOAD, RAW_PAYLOAD.RUN_ID.eq(run.runId())))
                .isPositive();

        // 13 days on, nothing of this run is old enough; 15 days on, everything is.
        Instant now = Instant.now(clock);
        runs.prunePayloads(now.plus(Duration.ofDays(13)).minus(IngestionSchedule.PAYLOAD_RETENTION));
        assertThat(dsl.fetchCount(RAW_PAYLOAD, RAW_PAYLOAD.RUN_ID.eq(run.runId())))
                .isPositive();
        runs.prunePayloads(now.plus(Duration.ofDays(15)).minus(IngestionSchedule.PAYLOAD_RETENTION));
        assertThat(dsl.fetchCount(RAW_PAYLOAD, RAW_PAYLOAD.RUN_ID.eq(run.runId())))
                .isZero();
    }
}
