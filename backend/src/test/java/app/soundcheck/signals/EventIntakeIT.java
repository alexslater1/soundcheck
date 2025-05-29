package app.soundcheck.signals;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.identity.internal.AccountPurgeService;
import app.soundcheck.signals.internal.EventRepository;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.MutableClock;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code POST /events} stores a batch of up to 100 events in one request, with the
 * user taken from the token and the time from the server; one malformed event rejects the whole batch
 * with field errors; a HIDE also hides the post; the endpoint is rate limited; a purged account's events
 * are anonymised.
 */
class EventIntakeIT extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(EventIntakeIT.class);
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    TestRestTemplate rest;

    @Autowired
    CatalogIngest catalogIngest;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    AccountPurgeService purge;

    @Autowired
    MutableClock clock;

    @Autowired
    JsonMapper json;

    TestApi api;
    TestApi.User viewer;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        viewer = api.register("watcher");
    }

    @AfterEach
    void backToRealTime() {
        clock.reset();
    }

    @Test
    void aHundredEventsAreStoredInOneRequestWithTheServersTimeAndTheTokensUser() {
        UUID session = UUID.randomUUID();
        UUID post = UUID.randomUUID();
        Instant longAgo = Instant.parse("2020-01-01T00:00:00Z");
        List<Map<String, Object>> batch = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            batch.add(event("IMPRESSION", post, Map.of("sessionId", session, "position", i, "clientTs", longAgo)));
        }
        batch.set(99, event("WATCH_PROGRESS", post, Map.of("sessionId", session, "position", 99, "value", 0.75)));
        Instant before = Instant.now(clock).minusSeconds(1);

        ResponseEntity<JsonNode> response = api.post("/api/v1/events", Map.of("events", batch), viewer);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Result<Record> stored = stored(viewer.id());
        assertThat(stored).hasSize(100);
        Record first = stored.getFirst();
        assertThat(first.get("event_type", String.class)).isEqualTo("IMPRESSION");
        assertThat(first.get("surface", String.class)).isEqualTo("FOR_YOU");
        assertThat(first.get("post_id", UUID.class)).isEqualTo(post);
        assertThat(first.get("feed_session_id", UUID.class)).isEqualTo(session);
        assertThat(first.get("position", Integer.class)).isZero();
        assertThat(first.get("platform", String.class)).isEqualTo("android"); // TestApi sends X-Client: android
        // The server's clock decides when it happened; the client's claim is kept beside it.
        assertThat(first.get("occurred_at", OffsetDateTime.class).toInstant())
                .isBetween(before, Instant.now(clock).plusSeconds(1));
        assertThat(first.get("client_ts", OffsetDateTime.class).toInstant()).isEqualTo(longAgo);
        Record last = stored.getLast();
        assertThat(last.get("event_type", String.class)).isEqualTo("WATCH_PROGRESS");
        assertThat(last.get("value", Float.class)).isEqualTo(0.75f);
        // One batch, one receive time.
        assertThat(stored.stream().map(r -> r.get("occurred_at")).distinct()).hasSize(1);
    }

    @Test
    void oneMalformedEventRejectsTheWholeBatchWithFieldErrors() {
        UUID post = UUID.randomUUID();
        List<Map<String, Object>> batch = new ArrayList<>();
        batch.add(event("IMPRESSION", post, Map.of()));
        batch.add(event("DANCED", post, Map.of())); // not a type
        batch.add(event("WATCH_PROGRESS", post, Map.of())); // no fraction
        batch.add(event("WATCH_PROGRESS", post, Map.of("value", 1.5))); // not a fraction
        Map<String, Object> noPost = new HashMap<>(event("LIKE", post, Map.of("position", -1)));
        noPost.remove("postId");
        noPost.put("surface", "MOON");
        batch.add(noPost);

        ResponseEntity<JsonNode> response = api.post("/api/v1/events", Map.of("events", batch), viewer);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("code").asText("")).isEqualTo("VALIDATION_FAILED");
        List<String> fields = new ArrayList<>();
        response.getBody()
                .path("errors")
                .forEach(e -> fields.add(e.path("field").asText("")));
        assertThat(fields)
                .containsExactlyInAnyOrder(
                        "events[1].type",
                        "events[2].valuePresentForWatchProgress",
                        "events[3].value",
                        "events[4].postId",
                        "events[4].surface",
                        "events[4].position");
        assertThat(stored(viewer.id())).isEmpty();

        // Too many, none, and no body at all.
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooMany.add(event("IMPRESSION", post, Map.of()));
        }
        assertThat(api.post("/api/v1/events", Map.of("events", tooMany), viewer).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.post("/api/v1/events", Map.of("events", List.of()), viewer)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.post("/api/v1/events", Map.of(), viewer).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(stored(viewer.id())).isEmpty();
        // And nobody can post events for someone else: there is no user field, only the token.
        assertThat(api.post("/api/v1/events", Map.of("events", List.of(event("LIKE", post, Map.of()))), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aHideEventAlsoHidesThePostAndAHideOfAnUnknownPostIsOnlyLogged() {
        TestData data = new TestData(catalogIngest, dsl, tx, events);
        TestApi.User author = api.register("clipmaker");
        String postId = api.post("/api/v1/posts", Map.of("mediaId", data.video(author.id(), "READY")), author)
                .getBody()
                .path("id")
                .asText("");
        UUID unknown = UUID.randomUUID();

        ResponseEntity<JsonNode> response = api.post(
                "/api/v1/events",
                Map.of(
                        "events",
                        List.of(event("HIDE", UUID.fromString(postId), Map.of()), event("HIDE", unknown, Map.of()))),
                viewer);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(stored(viewer.id())).hasSize(2);
        assertThat(dsl.fetchCount(dsl.selectFrom("content.post_hide")
                        .where("user_id = ? and post_id = ?", viewer.id(), UUID.fromString(postId))))
                .isEqualTo(1);
        assertThat(dsl.fetchCount(dsl.selectFrom("content.post_hide").where("post_id = ?", unknown)))
                .isZero();
    }

    /**
     * 120 requests a minute per user. The bucket refills continuously (two tokens a second),
     * so the first refusal comes a few requests after the 120th, depending on how long they took. Sent
     * with the JDK's HTTP client: the Apache client behind TestRestTemplate retries a 429 by itself and
     * would hide the limit.
     */
    @Test
    void theEndpointIsLimitedToAHundredAndTwentyRequestsAMinutePerUser() throws Exception {
        String body =
                json.writeValueAsString(Map.of("events", List.of(event("IMPRESSION", UUID.randomUUID(), Map.of()))));
        int accepted = 0;
        HttpResponse<String> limited = null;
        for (int i = 0; i < 200 && limited == null; i++) {
            HttpResponse<String> response = send(body, viewer);
            if (response.statusCode() == 429) {
                limited = response;
            } else {
                assertThat(response.statusCode()).isEqualTo(202);
                accepted++;
            }
        }

        assertThat(limited).as("a refusal within 200 requests").isNotNull();
        assertThat(accepted).isBetween(120, 140);
        assertThat(limited.body()).contains("\"code\":\"RATE_LIMITED\"");
        assertThat(limited.headers().firstValue("Retry-After").orElse("")).matches("\\d+");
        assertThat(limited.headers().firstValue("RateLimit-Limit")).contains("120");
        // A refused batch is not stored, and someone else is not affected.
        assertThat(stored(viewer.id())).hasSize(accepted);
        assertThat(send(body, api.register("other")).statusCode()).isEqualTo(202);
    }

    private HttpResponse<String> send(String body, TestApi.User user) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(rest.getRootUri() + "/api/v1/events"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + user.accessToken())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Informational: how long a typical batch takes here. Not an assertion about speed. */
    @Test
    void aTwentyEventBatchIsTimed() {
        List<Map<String, Object>> batch = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            batch.add(event("IMPRESSION", UUID.randomUUID(), Map.of("position", i)));
        }
        Map<String, Object> body = Map.of("events", batch);
        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            long start = System.nanoTime();
            assertThat(api.post("/api/v1/events", body, viewer).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            millis.add((System.nanoTime() - start) / 1_000_000);
        }
        List<Long> warm = millis.subList(10, millis.size()).stream().sorted().toList();
        long p50 = warm.get(warm.size() / 2);
        long p95 = warm.get((int) Math.ceil(warm.size() * 0.95) - 1);
        log.info(
                "POST /events, 20 events, 50 sequential requests after 10 warm-up (Testcontainers, one client): p50 {} ms, p95 {} ms",
                p50,
                p95);
        assertThat(stored(viewer.id())).hasSize(60 * 20);
    }

    @Test
    void aPurgedAccountsEventsStayUnderTheTombstoneId() {
        UUID post = UUID.randomUUID();
        api.post(
                "/api/v1/events",
                Map.of("events", List.of(event("IMPRESSION", post, Map.of()), event("LIKE", post, Map.of()))),
                viewer);
        dsl.execute(
                "insert into signals.user_artist_affinity (user_id, artist_id, score) values (?, ?, 0.5)",
                viewer.id(),
                UUID.randomUUID());
        assertThat(api.call(HttpMethod.DELETE, "/api/v1/me", Map.of("password", "correct horse battery"), viewer)
                        .getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        clock.advance(AccountPurgeService.GRACE.plus(Duration.ofHours(1)));
        purge.purgeDue();

        assertThat(stored(viewer.id())).isEmpty();
        assertThat(dsl.fetchCount(dsl.selectFrom("signals.interaction_event")
                        .where("post_id = ? and user_id = ?", post, EventRepository.TOMBSTONE)))
                .isEqualTo(2);
        assertThat(dsl.fetchCount(dsl.selectFrom("signals.user_artist_affinity").where("user_id = ?", viewer.id())))
                .isZero();
    }

    private static Map<String, Object> event(String type, UUID post, Map<String, Object> more) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", type);
        event.put("postId", post);
        event.put("surface", "FOR_YOU");
        event.putAll(more);
        return event;
    }

    private Result<Record> stored(UUID userId) {
        return dsl.fetch("select * from signals.interaction_event where user_id = ? order by id", userId);
    }
}
