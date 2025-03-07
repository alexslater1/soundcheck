package app.soundcheck.ingestion.internal;

import static app.soundcheck.shared.jooq.ingestion.Tables.INGESTION_RUN;
import static app.soundcheck.shared.jooq.ingestion.Tables.RAW_PAYLOAD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.ingestion.GigsIngested;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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
    void aSecondTriggerIsRejectedWhileOneIsRunningAndAnAbandonedRunIsClosed() {
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

            // Seven hours later the RUNNING row is treated as abandoned: it is closed and a new run starts.
            clock.advance(Duration.ofHours(7));
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
