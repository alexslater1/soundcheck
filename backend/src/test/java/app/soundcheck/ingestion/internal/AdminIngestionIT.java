package app.soundcheck.ingestion.internal;

import static app.soundcheck.shared.jooq.ingestion.Tables.INGESTION_RUN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.ingestion.internal.enrich.ArtistEnricher;
import app.soundcheck.ingestion.internal.enrich.VenueGeocoder;
import app.soundcheck.ingestion.internal.resolve.EntityResolver;
import app.soundcheck.ingestion.internal.source.CallBudget;
import app.soundcheck.ingestion.internal.source.GigSource;
import app.soundcheck.ingestion.internal.source.SourcePage;
import app.soundcheck.ingestion.internal.source.venues.VenueCoverageNotes;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.MutableClock;
import app.soundcheck.support.TestApi;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * "Run now" queues a run in the API, and a scheduler-role poller claims and runs it. The
 * poller is off in tests ({@code soundcheck.ingestion.queue.enabled: false}); each test drives it itself.
 */
class AdminIngestionIT extends IntegrationTest {

    static final String RUNS = "/api/v1/admin/ingestion/runs";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    IngestionService service;

    @Autowired
    IngestionRuns runs;

    @Autowired
    List<GigSource> sources;

    @Autowired
    EntityResolver resolver;

    @Autowired
    ArtistEnricher enricher;

    @Autowired
    VenueGeocoder geocoder;

    @Autowired
    VenueCoverageNotes coverageNotes;

    @Autowired
    CatalogIngest catalog;

    @Autowired
    IngestionProperties properties;

    @Autowired
    MeterRegistry meters;

    @Autowired
    MutableClock clock;

    @Autowired
    DSLContext dsl;

    TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
    }

    /** No queued or running run outlives its test: it would block the source for every later test. */
    @AfterEach
    void closeOpenRuns() {
        clock.reset();
        dsl.update(INGESTION_RUN)
                .set(INGESTION_RUN.STATUS, "FAILED")
                .set(INGESTION_RUN.ERROR, "closed by AdminIngestionIT")
                .where(INGESTION_RUN.STATUS.in("QUEUED", "RUNNING"))
                .execute();
    }

    @Test
    void runNowQueuesARunThatThePollerRunsAndTheConsoleListsWithItsIssues() {
        TestApi.User admin = api.admin();

        ResponseEntity<JsonNode> queued = api.post(RUNS, Map.of("source", "TICKETMASTER"), admin);
        assertThat(queued.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID runId = UUID.fromString(queued.getBody().path("runId").asText(""));

        JsonNode waiting = api.get(RUNS + "/" + runId, admin).getBody().path("run");
        assertThat(waiting.path("status").asText("")).isEqualTo("QUEUED");
        assertThat(waiting.path("trigger").asText("")).isEqualTo("CONSOLE");
        assertThat(waiting.path("requestedBy").asText("")).isEqualTo(admin.id().toString());
        assertThat(waiting.path("queuedAt").isNull()).isFalse();
        JsonNode ticketmaster = source("TICKETMASTER", admin);
        assertThat(ticketmaster.path("enabled").asBoolean()).isTrue();
        assertThat(ticketmaster.path("mode").asText("")).isEqualTo("fixtures");
        assertThat(ticketmaster.path("lastRun").path("id").asText("")).isEqualTo(runId.toString());

        // While it is queued, neither a second "Run now" nor the schedule's path may start the source.
        ResponseEntity<JsonNode> again = api.post(RUNS, Map.of("source", "TICKETMASTER"), admin);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().path("code").asText("")).isEqualTo("INGESTION_ALREADY_RUNNING");
        assertThatThrownBy(() -> service.run("TICKETMASTER", "TEST"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("INGESTION_ALREADY_RUNNING"));

        // The scheduler's poller. Its guard allows mixing: other ITs leave real-looking listings in the
        // shared database, and the guard itself is covered by IngestionGuardTest.
        Optional<IngestionService.RunResult> result =
                poller(serviceWith(sources, true)).runNext();
        assertThat(result).hasValueSatisfying(r -> {
            assertThat(r.runId()).isEqualTo(runId);
            assertThat(r.status()).isEqualTo("SUCCEEDED");
        });
        assertThat(poller(serviceWith(sources, true)).runNext())
                .as("the queue is empty")
                .isEmpty();

        JsonNode done = api.get(RUNS + "/" + runId, admin).getBody();
        assertThat(done.path("run").path("status").asText("")).isEqualTo("SUCCEEDED");
        assertThat(done.path("run").path("fetched").asInt()).isEqualTo(240);
        assertThat(done.path("run").path("issueCounts").path("NO_ARTISTS").asInt())
                .isEqualTo(6);
        assertThat(Instant.parse(done.path("run").path("startedAt").asText("")))
                .isAfterOrEqualTo(
                        Instant.parse(done.path("run").path("queuedAt").asText("")));
        assertThat(done.path("stats").path("gigs").has("matchedByExternalId")).isTrue();
        assertThat(done.path("issues").size()).isPositive();
        assertThat(done.path("issues").findValuesAsText("kind")).contains("NO_ARTISTS");

        assertThat(findInList(RUNS + "?source=TICKETMASTER&status=SUCCEEDED", runId, admin))
                .as("listed with the filters")
                .isTrue();
        assertThat(api.get(RUNS + "/" + UUID.randomUUID(), admin).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void stoppingAQueuedRunClosesItAtOnceAndFreesTheSource() {
        TestApi.User admin = api.admin();
        UUID runId = queue("TICKETMASTER", admin);

        ResponseEntity<JsonNode> stopped = api.post(RUNS + "/" + runId + "/stop", Map.of(), admin);
        assertThat(stopped.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stopped.getBody().path("status").asText("")).isEqualTo("STOPPED");
        assertThat(stopped.getBody().path("stopRequestedAt").isNull()).isFalse();
        assertThat(stopped.getBody().path("finishedAt").isNull()).isFalse();
        assertThat(poller(serviceWith(sources, true)).runNext())
                .as("nothing left to run")
                .isEmpty();

        ResponseEntity<JsonNode> again = api.post(RUNS + "/" + runId + "/stop", Map.of(), admin);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().path("code").asText("")).isEqualTo("RUN_ENDED");
        assertThat(api.post(RUNS + "/" + UUID.randomUUID() + "/stop", Map.of(), admin)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(findInList(RUNS + "?status=STOPPED", runId, admin)).isTrue();
        // The source is free again.
        assertThat(api.post(RUNS, Map.of("source", "TICKETMASTER"), admin).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void aRunningRunStopsAtItsNextHeartbeatAndKeepsWhatItIngested() {
        TestApi.User admin = api.admin();
        UUID runId = queue("TICKETMASTER", admin);
        GigSource ticketmaster = sources.stream()
                .filter(s -> s.name().equals("TICKETMASTER"))
                .findFirst()
                .orElseThrow();
        // Stop is pressed while the run is RUNNING: as its first page is read, before its first gig.
        GigSource stopping = new GigSource() {
            @Override
            public String name() {
                return ticketmaster.name();
            }

            @Override
            public Window window(Instant today, Duration horizon) {
                return ticketmaster.window(today, horizon);
            }

            @Override
            public boolean synthetic() {
                return ticketmaster.synthetic();
            }

            @Override
            public Stream<SourcePage> pages(Instant from, Instant to, CallBudget budget) {
                ResponseEntity<JsonNode> pressed = api.post(RUNS + "/" + runId + "/stop", Map.of(), admin);
                assertThat(pressed.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(pressed.getBody().path("status").asText("")).isEqualTo("RUNNING");
                assertThat(pressed.getBody().path("stopRequestedAt").isNull()).isFalse();
                return ticketmaster.pages(from, to, budget);
            }
        };

        Optional<IngestionService.RunResult> result =
                poller(serviceWith(List.of(stopping), true)).runNext();

        assertThat(result).hasValueSatisfying(r -> assertThat(r.status()).isEqualTo("STOPPED"));
        JsonNode run = api.get(RUNS + "/" + runId, admin).getBody().path("run");
        assertThat(run.path("status").asText("")).isEqualTo("STOPPED");
        assertThat(run.path("error").asText("")).isEqualTo("stopped from the console");
        assertThat(run.path("finishedAt").isNull()).isFalse();
        // It stopped at the first gig's heartbeat, long before the fixtures' 240 listings.
        assertThat(run.path("fetched").asInt()).isBetween(1, 239);
    }

    @Test
    void aSourceThisDeploymentDoesNotRunCannotBeQueued() {
        TestApi.User admin = api.admin();
        for (String source : List.of("VENUE", "SETLISTFM", "NOPE")) {
            ResponseEntity<JsonNode> response = api.post(RUNS, Map.of("source", source), admin);
            assertThat(response.getStatusCode()).as(source).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().path("code").asText("")).as(source).isEqualTo("SOURCE_NOT_ENABLED");
        }
        assertThat(source("VENUE", admin).path("enabled").asBoolean()).isFalse(); // fixtures mode
        assertThat(dsl.fetchCount(INGESTION_RUN, INGESTION_RUN.STATUS.eq("QUEUED")))
                .isZero();
    }

    @Test
    void theDailyLoopSkipsASourceThatIsAlreadyQueuedAndRunsTheOthers() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        CountingSource queuedOne = new CountingSource("QUEUED-" + tag, Duration.ZERO);
        CountingSource other = new CountingSource("OTHER-" + tag, Duration.ZERO);
        UUID queued = runs.enqueue(queuedOne.name(), "CONSOLE", UUID.randomUUID(), Instant.now(clock));
        List<GigSource> both = List.of(queuedOne, other);
        IngestionSchedule schedule = new IngestionSchedule(
                serviceWith(both, false),
                runs,
                both,
                new IngestionGuard(catalog, false),
                coverageNotes,
                geocoder,
                properties,
                clock);

        schedule.ingestDaily();

        assertThat(queuedOne.calls.get()).as("the queued source was not read").isZero();
        assertThat(other.calls.get()).isEqualTo(1);
        assertThat(dsl.select(INGESTION_RUN.ID, INGESTION_RUN.STATUS)
                        .from(INGESTION_RUN)
                        .where(INGESTION_RUN.SOURCE.eq(queuedOne.name()))
                        .fetchMap(INGESTION_RUN.ID, INGESTION_RUN.STATUS))
                .as("no second row for the queued source; its queued run is untouched")
                .isEqualTo(Map.of(queued, "QUEUED"));
        assertThat(dsl.select(INGESTION_RUN.TRIGGER, INGESTION_RUN.STATUS)
                        .from(INGESTION_RUN)
                        .where(INGESTION_RUN.SOURCE.eq(other.name()))
                        .fetchOne()
                        .intoList())
                .containsExactly("SCHEDULE", "SUCCEEDED");
    }

    @Test
    void twoPollersAtOnceRunAQueuedRunOnce() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        // Slow enough that the second poller arrives while the first is still running.
        CountingSource slow = new CountingSource("SLOW-" + tag, Duration.ofMillis(300));
        UUID queued = runs.enqueue(slow.name(), "CONSOLE", UUID.randomUUID(), Instant.now(clock));
        IngestionService pollerService = serviceWith(List.of(slow), false);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Optional<IngestionService.RunResult>>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return pollerService.runQueued(queued);
                }));
            }
            go.countDown();
            List<Optional<IngestionService.RunResult>> outcomes = new ArrayList<>();
            for (Future<Optional<IngestionService.RunResult>> f : results) {
                outcomes.add(f.get());
            }
            assertThat(outcomes.stream().filter(Optional::isPresent)).hasSize(1);
        }
        assertThat(slow.calls.get()).as("the source was read once").isEqualTo(1);
        assertThat(dsl.fetchCount(INGESTION_RUN, INGESTION_RUN.SOURCE.eq(slow.name())))
                .isEqualTo(1);
        assertThat(dsl.fetchValue(INGESTION_RUN.STATUS, INGESTION_RUN.ID.eq(queued)))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void aQueuedRunOfASourceTheSchedulerLacksEndsFailedAndAnUnclaimedRunExpires() {
        String ghost = "GHOST-" + UUID.randomUUID().toString().substring(0, 8);
        UUID queued = runs.enqueue(ghost, "CONSOLE", UUID.randomUUID(), Instant.now(clock));
        assertThat(service.runQueued(queued))
                .hasValueSatisfying(r -> assertThat(r.status()).isEqualTo("FAILED"));
        assertThat(dsl.fetchValue(INGESTION_RUN.ERROR, INGESTION_RUN.ID.eq(queued)))
                .startsWith("SOURCE_UNKNOWN");
        assertThat(service.runQueued(queued))
                .as("closed runs are never claimed again")
                .isEmpty();

        // A run nobody claims (no scheduler process) blocks the source for two hours at most.
        UUID stuck = runs.enqueue(ghost, "CONSOLE", UUID.randomUUID(), Instant.now(clock));
        clock.advance(Duration.ofMinutes(119));
        assertThatThrownBy(() -> runs.enqueue(ghost, "CONSOLE", UUID.randomUUID(), Instant.now(clock)))
                .isInstanceOf(ApiException.class);
        clock.advance(Duration.ofMinutes(2));
        UUID next = runs.enqueue(ghost, "CONSOLE", UUID.randomUUID(), Instant.now(clock));
        assertThat(dsl.fetchValue(INGESTION_RUN.STATUS, INGESTION_RUN.ID.eq(stuck)))
                .isEqualTo("FAILED");
        assertThat(dsl.fetchValue(INGESTION_RUN.ERROR, INGESTION_RUN.ID.eq(stuck)))
                .startsWith("never claimed");
        assertThat(dsl.fetchValue(INGESTION_RUN.STATUS, INGESTION_RUN.ID.eq(next)))
                .isEqualTo("QUEUED");
    }

    @Test
    void theRunListPagesWithACursor() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String name = "PAGED-" + tag;
        CountingSource source = new CountingSource(name, Duration.ZERO);
        IngestionService paged = serviceWith(List.of(source), false);
        for (int i = 0; i < 3; i++) {
            paged.run(name, "TEST");
            clock.advance(Duration.ofSeconds(1));
        }
        TestApi.User admin = api.admin();
        List<String> seen = new ArrayList<>();
        String cursor = "";
        do {
            JsonNode page = api.get(RUNS + "?source=" + name + "&limit=2&cursor=" + cursor, admin)
                    .getBody();
            page.path("items").forEach(item -> seen.add(item.path("id").asText("")));
            cursor = page.path("nextCursor").isNull()
                    ? null
                    : page.path("nextCursor").asText("");
        } while (cursor != null && seen.size() < 10);
        assertThat(seen).hasSize(3).doesNotHaveDuplicates();
        assertThat(api.get(RUNS + "?cursor=not-a-cursor", admin).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private JsonNode source(String name, TestApi.User admin) {
        for (JsonNode s : api.get("/api/v1/admin/ingestion/sources", admin).getBody()) {
            if (s.path("name").asText("").equals(name)) {
                return s;
            }
        }
        throw new AssertionError("no source " + name);
    }

    private UUID queue(String source, TestApi.User admin) {
        ResponseEntity<JsonNode> queued = api.post(RUNS, Map.of("source", source), admin);
        assertThat(queued.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return UUID.fromString(queued.getBody().path("runId").asText(""));
    }

    private boolean findInList(String path, UUID runId, TestApi.User admin) {
        String cursor = "";
        for (int page = 0; page < 20 && cursor != null; page++) {
            JsonNode body = api.get(path + "&limit=50&cursor=" + cursor, admin).getBody();
            for (JsonNode item : body.path("items")) {
                if (item.path("id").asText("").equals(runId.toString())) {
                    return true;
                }
            }
            cursor = body.path("nextCursor").isNull()
                    ? null
                    : body.path("nextCursor").asText("");
        }
        return false;
    }

    private IngestionQueue poller(IngestionService withService) {
        return new IngestionQueue(withService, runs, true);
    }

    private IngestionService serviceWith(List<GigSource> withSources, boolean allowMixed) {
        return new IngestionService(
                withSources,
                runs,
                resolver,
                enricher,
                geocoder,
                catalog,
                new IngestionGuard(catalog, allowMixed),
                properties,
                meters,
                clock);
    }

    /** A source with no gigs that counts how often it is read, and can take its time. */
    static final class CountingSource implements GigSource {
        final String name;
        final Duration delay;
        final AtomicInteger calls = new AtomicInteger();

        CountingSource(String name, Duration delay) {
            this.name = name;
            this.delay = delay;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Stream<SourcePage> pages(Instant from, Instant to, CallBudget budget) {
            calls.incrementAndGet();
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Stream.empty();
        }
    }
}
