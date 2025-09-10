package app.soundcheck.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.content.internal.PostService;
import app.soundcheck.media.JobQueue.QueueMessage;
import app.soundcheck.media.internal.PipelineQueries;
import app.soundcheck.media.internal.worker.TranscodeWorker;
import app.soundcheck.media.internal.worker.TranscodeWorker.Outcome;
import app.soundcheck.media.internal.worker.WorkerProperties;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The pipeline console's API against real PostGIS, LocalStack SQS and, for the
 * retry path, the real worker and ffmpeg. The stranded rule, Retry (a failed clip's post comes back and
 * is published when the clip is READY; two retries still transcode once), Fail (a stranded clip's post
 * fails), and the refusals.
 */
class PipelineAdminIT extends IntegrationTest {

    static final ZoneId UK = ZoneId.of("Europe/London");
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    static Path dir;

    static byte[] clip;

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
    TranscodeWorker worker;

    @Autowired
    JobQueue queue;

    @Autowired
    PipelineQueries pipeline;

    @Autowired
    PostService posts;

    @Autowired
    WorkerProperties workerProps;

    @Autowired
    Clock clock;

    TestApi api;
    TestData data;
    TestApi.User fan;
    TestApi.User admin;

    /** 2 s of a test pattern with a tone: original material, made on the spot. */
    @BeforeAll
    static void generateClip() throws Exception {
        Path file = dir.resolve("clip.mp4");
        Process process = new ProcessBuilder(
                        "ffmpeg",
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-nostdin",
                        "-y",
                        "-f",
                        "lavfi",
                        "-i",
                        "testsrc2=duration=2:size=640x360:rate=30",
                        "-f",
                        "lavfi",
                        "-i",
                        "sine=frequency=440:duration=2",
                        "-c:v",
                        "libx264",
                        "-preset",
                        "veryfast",
                        "-pix_fmt",
                        "yuv420p",
                        "-c:a",
                        "aac",
                        "-shortest",
                        "-f",
                        "mp4",
                        file.toString())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("generate.log").toFile())
                .start();
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue())
                .as(Files.readString(dir.resolve("generate.log")))
                .isZero();
        clip = Files.readAllBytes(file);
    }

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
        fan = api.register("pipeline");
        admin = api.admin();
        drainQueue();
    }

    @AfterEach
    void tearDown() {
        // Retries send real messages; other test classes' workers must not find them.
        drainQueue();
    }

    // ---- The read endpoint and the stranded rule --------------------------------------------------

    @Test
    void theOverviewListsStrandedAndFailedClipsAndReadsBothQueues() {
        Instant now = clock.instant();
        UUID lostMessage = data.video(fan.id(), "UPLOADED");
        touch(lostMessage, now.minus(Duration.ofMinutes(11)));
        UUID liveJob = data.video(fan.id(), "UPLOADED");
        claim(liveJob, "PROCESSING", now.minusSeconds(30)); // the heartbeat renewed it just now
        UUID uploadedButRunning = data.video(fan.id(), "UPLOADED");
        touch(uploadedButRunning, now.minus(Duration.ofMinutes(11)));
        runningJob(uploadedButRunning, now.minus(Duration.ofMinutes(12)));
        UUID deadWorker = data.video(fan.id(), "UPLOADED");
        claim(deadWorker, "PROCESSING", now.minus(workerProps.lease()).minusSeconds(60));
        UUID failed = data.video(fan.id(), "FAILED");
        UUID freshUpload = data.video(fan.id(), "UPLOADED");
        touch(freshUpload, now.minus(Duration.ofMinutes(9)));

        // The one rule: UPLOADED > 10 min with no RUNNING attempt, or PROCESSING past the lease.
        assertThat(pipeline.isStranded(lostMessage, now)).isTrue();
        assertThat(pipeline.isStranded(deadWorker, now)).isTrue();
        assertThat(pipeline.isStranded(liveJob, now)).as("fresh heartbeat").isFalse();
        assertThat(pipeline.isStranded(uploadedButRunning, now))
                .as("an attempt is running")
                .isFalse();
        assertThat(pipeline.isStranded(freshUpload, now)).as("9 min").isFalse();
        assertThat(pipeline.isStranded(failed, now))
                .as("FAILED is not stranded")
                .isFalse();

        ResponseEntity<JsonNode> response = api.get("/api/v1/admin/pipeline", admin);
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        // LocalStack answers for both queues.
        assertThat(body.path("queueError").isNull()).isTrue();
        assertThat(body.path("queue").path("visible").isInt()).isTrue();
        assertThat(body.path("queue").path("inFlight").isInt()).isTrue();
        assertThat(body.path("dlq").path("visible").isInt()).isTrue();
        assertThat(body.path("strandedRule").path("uploadedMinutes").asInt()).isEqualTo(10);
        assertThat(body.path("strandedRule").path("leaseMinutes").asInt()).isEqualTo(15);
        assertThat(statusCount(body, "FAILED")).isPositive();

        Map<String, JsonNode> attention = new java.util.HashMap<>();
        body.path("attention").forEach(a -> attention.put(a.path("id").asText(""), a));
        assertThat(attention).containsKeys(lostMessage.toString(), deadWorker.toString(), failed.toString());
        assertThat(attention)
                .doesNotContainKeys(liveJob.toString(), uploadedButRunning.toString(), freshUpload.toString());
        assertThat(attention.get(lostMessage.toString()).path("stranded").asBoolean())
                .isTrue();
        assertThat(attention.get(deadWorker.toString()).path("status").asText(""))
                .isEqualTo("PROCESSING");
        assertThat(attention.get(failed.toString()).path("stranded").asBoolean())
                .isFalse();

        // Range checks.
        assertThat(api.get("/api/v1/admin/pipeline?from=2026-01-01T00:00:00Z&to=2025-01-01T00:00:00Z", admin)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.get("/api/v1/admin/pipeline?from=2025-01-01T00:00:00Z&to=2026-01-01T00:00:00Z", admin)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // Operators only.
        assertThat(api.get("/api/v1/admin/pipeline", fan).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- Retry, end to end ----------------------------------------------------------------------

    @Test
    void aFailedClipRetriedTwiceIsTranscodedOnceAndItsPostComesBackAndIsPublished() throws Exception {
        UUID mediaId = upload(clip);
        String postId = post(mediaId, null);
        assertThat(postStatus(postId)).isEqualTo("PENDING_MEDIA");
        // The pipeline gave up on it (its message is gone): the clip FAILED, so did the post.
        deleteMessageFor(mediaId);
        data.workerGivesUp(mediaId, fan.id(), false);
        await().atMost(Duration.ofSeconds(10)).until(() -> postStatus(postId).equals("FAILED"));

        ResponseEntity<JsonNode> retried = api.post("/api/v1/admin/pipeline/media/" + mediaId + "/retry", null, admin);
        assertThat(retried.getStatusCode())
                .as(String.valueOf(retried.getBody()))
                .isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody().path("previousStatus").asText("")).isEqualTo("FAILED");
        assertThat(retried.getBody().path("status").asText("")).isEqualTo("UPLOADED");
        // A fresh set of attempts, the failure and the lease cleared.
        assertThat(asset(mediaId, "status")).isEqualTo("UPLOADED");
        assertThat(asset(mediaId, "attempts")).isEqualTo(0);
        assertThat(asset(mediaId, "failure_reason")).isNull();
        // The post waits for its clip again.
        await().atMost(Duration.ofSeconds(10)).until(() -> postStatus(postId).equals("PENDING_MEDIA"));

        // A second retry of a clip that is now simply in line is refused …
        ResponseEntity<JsonNode> tooSoon = api.post("/api/v1/admin/pipeline/media/" + mediaId + "/retry", null, admin);
        assertThat(tooSoon.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tooSoon.getBody().path("code").asText("")).isEqualTo("MEDIA_NOT_RETRYABLE");
        // … but once it looks stranded (its message seems lost) it is accepted, and two messages are queued.
        touch(mediaId, clock.instant().minus(Duration.ofMinutes(11)));
        assertThat(api.post("/api/v1/admin/pipeline/media/" + mediaId + "/retry", null, admin)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // Two messages, one transcode: the second delivery finds the clip READY and is dropped.
        List<Outcome> outcomes = new ArrayList<>();
        outcomes.add(pollUntilHandled());
        outcomes.add(pollUntilHandled());
        assertThat(outcomes).containsExactly(Outcome.READY, Outcome.DUPLICATE);
        assertThat(dsl.fetchCount(dsl.selectFrom("media.transcode_job").where("media_id = ?", mediaId)))
                .isEqualTo(1);
        assertThat(asset(mediaId, "status")).isEqualTo("READY");
        assertThat(asset(mediaId, "attempts")).isEqualTo(1);

        await().atMost(Duration.ofSeconds(10)).until(() -> postStatus(postId).equals("PUBLISHED"));
    }

    @Test
    void aRejectedOrHealthyClipIsNotRetried() {
        UUID rejected = data.video(fan.id(), "REJECTED");
        ResponseEntity<JsonNode> response =
                api.post("/api/v1/admin/pipeline/media/" + rejected + "/retry", null, admin);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().path("code").asText("")).isEqualTo("MEDIA_REJECTED");

        for (String status : List.of("READY", "DELETED", "AWAITING_UPLOAD")) {
            UUID id = data.video(fan.id(), status);
            ResponseEntity<JsonNode> refused = api.post("/api/v1/admin/pipeline/media/" + id + "/retry", null, admin);
            assertThat(refused.getStatusCode()).as(status).isEqualTo(HttpStatus.CONFLICT);
            assertThat(refused.getBody().path("code").asText("")).as(status).isEqualTo("MEDIA_NOT_RETRYABLE");
        }
        UUID live = data.video(fan.id(), "UPLOADED");
        claim(live, "PROCESSING", clock.instant());
        assertThat(api.post("/api/v1/admin/pipeline/media/" + live + "/retry", null, admin)
                        .getBody()
                        .path("code")
                        .asText(""))
                .isEqualTo("MEDIA_NOT_RETRYABLE");
        assertThat(asset(live, "status")).isEqualTo("PROCESSING");

        assertThat(api.post("/api/v1/admin/pipeline/media/" + UUID.randomUUID() + "/retry", null, admin)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.post("/api/v1/admin/pipeline/media/" + rejected + "/retry", null, fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aDeletedPostsClipIsReleasedSoItCannotBeRetried() {
        UUID mediaId = data.video(fan.id(), "UPLOADED");
        String postId = post(mediaId, null);
        data.workerGivesUp(mediaId, fan.id(), false);
        await().atMost(Duration.ofSeconds(10)).until(() -> postStatus(postId).equals("FAILED"));
        assertThat(api.delete("/api/v1/posts/" + postId, fan).getStatusCode().is2xxSuccessful())
                .isTrue();

        // Deleting the post released its clip: there is nothing left to retry, and a retry must
        // not bring a released asset back to life.
        ResponseEntity<JsonNode> retried = api.post("/api/v1/admin/pipeline/media/" + mediaId + "/retry", null, admin);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(retried.getBody().path("code").asText("")).isEqualTo("MEDIA_NOT_RETRYABLE");
        assertThat(dsl.fetchValue("select status from media.media_asset where id = ?", mediaId))
                .isEqualTo("DELETED");
        assertThat(postStatus(postId)).isEqualTo("DELETED");
        assertThat(posts.mediaRequeued(mediaId)).isFalse();
        // Even when the clip then becomes READY, the deleted post is not published.
        data.workerFinishes(mediaId, fan.id());
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).until(() -> postStatus(postId)
                .equals("DELETED"));
    }

    @Test
    void aFailedPostWhoseGigAndDayHaveANewerOpenPostStaysFailed() {
        UUID venue = data.venue("Pipeline Hall " + UUID.randomUUID());
        UUID act = data.artist("Pipeline Act " + UUID.randomUUID());
        Instant at = LocalDate.now(UK).minusDays(2).atTime(20, 0).atZone(UK).toInstant();
        UUID gig = data.gig("Pipeline night", venue, at, act);
        UUID first = data.video(fan.id(), "UPLOADED");
        String failedPost = post(first, gig);
        data.workerGivesUp(first, fan.id(), false);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> postStatus(failedPost).equals("FAILED"));
        // The author posted again from the same gig and day: that post is now the open one.
        String newer = post(data.video(fan.id(), "READY"), gig);
        assertThat(postStatus(newer)).isEqualTo("PUBLISHED");

        assertThat(api.post("/api/v1/admin/pipeline/media/" + first + "/retry", null, admin)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(posts.mediaRequeued(first)).isFalse();
        assertThat(postStatus(failedPost)).isEqualTo("FAILED");
        assertThat(postStatus(newer)).isEqualTo("PUBLISHED");
    }

    // ---- Fail -------------------------------------------------------------------------------------

    @Test
    void failingAStrandedClipFailsItsPostAndOnlyAStrandedClipCanBeFailed() {
        Instant now = clock.instant();
        UUID mediaId = data.video(fan.id(), "UPLOADED");
        String postId = post(mediaId, null);
        // Its worker died mid-job: PROCESSING, claim past the lease, the attempt still RUNNING.
        claim(mediaId, "PROCESSING", now.minus(workerProps.lease()).minusSeconds(60));
        runningJob(mediaId, now.minus(workerProps.lease()).minusSeconds(60));

        ResponseEntity<JsonNode> failed = api.post("/api/v1/admin/pipeline/media/" + mediaId + "/fail", null, admin);
        assertThat(failed.getStatusCode()).as(String.valueOf(failed.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(failed.getBody().path("previousStatus").asText("")).isEqualTo("PROCESSING");
        assertThat(asset(mediaId, "status")).isEqualTo("FAILED");
        assertThat(asset(mediaId, "failure_reason")).isEqualTo("OPERATOR");
        assertThat(asset(mediaId, "claimed_by")).isNull();
        assertThat(dsl.fetchValue("select error from media.transcode_job where media_id = ?", mediaId)
                        .toString())
                .startsWith("OPERATOR");
        await().atMost(Duration.ofSeconds(10)).until(() -> postStatus(postId).equals("FAILED"));

        // Already FAILED: not stranded, so Fail is refused (Retry is what it offers now).
        ResponseEntity<JsonNode> again = api.post("/api/v1/admin/pipeline/media/" + mediaId + "/fail", null, admin);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().path("code").asText("")).isEqualTo("MEDIA_NOT_STRANDED");
        // A clip a worker is still on can't be failed either.
        UUID live = data.video(fan.id(), "UPLOADED");
        claim(live, "PROCESSING", now);
        assertThat(api.post("/api/v1/admin/pipeline/media/" + live + "/fail", null, admin)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(asset(live, "status")).isEqualTo("PROCESSING");
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private String post(UUID mediaId, UUID gigId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("clips", List.of(Map.of("mediaId", mediaId)));
        if (gigId != null) {
            body.put("gigId", gigId);
        }
        ResponseEntity<JsonNode> response = api.post("/api/v1/posts", body, fan);
        assertThat(response.getStatusCode())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("id").asText("");
    }

    private String postStatus(String postId) {
        return String.valueOf(dsl.fetchValue("select status from content.post where id = ?", UUID.fromString(postId)));
    }

    private Object asset(UUID mediaId, String column) {
        return dsl.fetchValue("select " + column + " from media.media_asset where id = ?", mediaId);
    }

    private void touch(UUID mediaId, Instant at) {
        dsl.execute("update media.media_asset set updated_at = ?::timestamptz where id = ?", utc(at), mediaId);
    }

    private void claim(UUID mediaId, String status, Instant at) {
        dsl.execute(
                "update media.media_asset set status = ?, attempts = 1, claimed_by = 'gone-worker', claimed_at = ?::timestamptz,"
                        + " updated_at = ?::timestamptz where id = ?",
                status,
                utc(at),
                utc(at),
                mediaId);
    }

    private void runningJob(UUID mediaId, Instant startedAt) {
        dsl.execute(
                "insert into media.transcode_job (id, media_id, attempt, worker_id, status, started_at)"
                        + " values (?, ?, 1, 'gone-worker', 'RUNNING', ?::timestamptz)",
                UUID.randomUUID(),
                mediaId,
                utc(startedAt));
    }

    private static int statusCount(JsonNode body, String status) {
        for (JsonNode s : body.path("statuses")) {
            if (s.path("status").asText("").equals(status)) {
                return s.path("count").asInt();
            }
        }
        return 0;
    }

    private static OffsetDateTime utc(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }

    private void drainQueue() {
        List<QueueMessage> leftovers;
        while (!(leftovers = queue.receive(10, Duration.ZERO)).isEmpty()) {
            leftovers.forEach(m -> queue.delete(m.receiptHandle()));
        }
    }

    /** Waits for the upload's own message (the relay sends it just after the commit) and deletes it. */
    private void deleteMessageFor(UUID mediaId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            for (QueueMessage message : queue.receive(10, Duration.ofSeconds(1))) {
                queue.delete(message.receiptHandle());
                if (message.body().contains(mediaId.toString())) {
                    return;
                }
            }
        }
        throw new AssertionError("the upload's message never arrived");
    }

    /** Uploads through the API as a client would: ticket, PUT to the presigned URL, complete. */
    private UUID upload(byte[] bytes) throws Exception {
        ResponseEntity<JsonNode> created = api.post(
                "/api/v1/media/uploads",
                Map.of("kind", "VIDEO", "contentType", "video/mp4", "bytes", bytes.length, "durationMs", 2_000),
                fan);
        assertThat(created.getStatusCode())
                .as(String.valueOf(created.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        JsonNode ticket = created.getBody();
        HttpResponse<Void> put = HTTP.send(
                HttpRequest.newBuilder(URI.create(ticket.path("putUrl").asText("")))
                        .header("Content-Type", "video/mp4")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(put.statusCode()).isEqualTo(200);
        String mediaId = ticket.path("mediaId").asText("");
        assertThat(api.post("/api/v1/media/uploads/" + mediaId + "/complete", null, fan)
                        .getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        return UUID.fromString(mediaId);
    }

    /** Polls until the worker has handled one message (the relay sends it just after the commit). */
    private Outcome pollUntilHandled() {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            List<Outcome> outcomes = worker.pollOnce(Duration.ofSeconds(1));
            if (!outcomes.isEmpty()) {
                return outcomes.getFirst();
            }
        }
        throw new AssertionError("no message arrived within 30 s");
    }
}
