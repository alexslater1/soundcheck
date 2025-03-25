package app.soundcheck.media.internal.worker;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.media.JobQueue;
import app.soundcheck.media.JobQueue.QueueMessage;
import app.soundcheck.media.MediaFailed;
import app.soundcheck.media.MediaReady;
import app.soundcheck.media.ObjectStorage;
import app.soundcheck.media.internal.transcode.MediaRejectedException;
import app.soundcheck.media.internal.transcode.ProbeResult;
import app.soundcheck.media.internal.transcode.Rung;
import app.soundcheck.media.internal.worker.TranscodeJobs.Claim;
import app.soundcheck.media.internal.worker.TranscodeWorker.Outcome;
import app.soundcheck.shared.observability.Spans;
import app.soundcheck.shared.observability.Traceparent;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.Parallel;
import app.soundcheck.support.TestApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * The pipeline end to end: a real clip uploaded through the API, a real
 * queue (LocalStack SQS), the real worker and real ffmpeg. The polling loops are off in tests
 * ({@code soundcheck.worker.enabled=false}); each test drives the worker one poll at a time, so every
 * outcome can be asserted. Failure and shutdown cases use a second worker built by hand around a
 * transcoder that misbehaves on demand.
 */
@RecordApplicationEvents
class TranscodePipelineIT extends IntegrationTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    static Path dir;

    static byte[] clip;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    TranscodeWorker worker;

    @Autowired
    TranscodeJobs jobs;

    @Autowired
    Transcoder realTranscoder;

    @Autowired
    JobQueue queue;

    @Autowired
    ObjectStorage storage;

    @Autowired
    S3Client s3;

    @Autowired
    SqsClient sqs;

    @Autowired
    DSLContext dsl;

    @Autowired
    WorkerProperties props;

    @Autowired
    JsonMapper json;

    @Autowired
    Clock clock;

    @Autowired
    MeterRegistry meters;

    @Autowired
    Spans spans;

    @Autowired
    Traceparent traceparent;

    @Autowired
    ApplicationEvents events;

    TestApi api;
    TestApi.User owner;

    /** 5 s of a moving test pattern with a tone, 1280×720: original material, made on the spot. */
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
                        "testsrc2=duration=5:size=1280x720:rate=30",
                        "-f",
                        "lavfi",
                        "-i",
                        "sine=frequency=440:duration=5",
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
        owner = api.register("clipper");
        // Other test classes complete uploads too; their messages are not this test's business.
        List<QueueMessage> leftovers;
        while (!(leftovers = queue.receive(10, Duration.ZERO)).isEmpty()) {
            leftovers.forEach(m -> queue.delete(m.receiptHandle()));
        }
    }

    @Test
    void anUploadedClipBecomesAPlayableLadderAndADuplicateMessageIsANoOp() throws Exception {
        UUID mediaId = upload(clip);

        assertThat(pollUntilHandled(worker)).isEqualTo(Outcome.READY);

        // The API now hands out playback URLs under the versioned prefix.
        String prefix = "media/" + mediaId + "/v1/";
        JsonNode playback = api.get("/api/v1/media/" + mediaId, owner).getBody();
        assertThat(playback.path("status").asText("")).isEqualTo("READY");
        assertThat(playback.path("hlsUrl").asText("")).endsWith("/" + prefix + "master.m3u8");
        assertThat(playback.path("posterUrl").asText("")).endsWith("/" + prefix + "poster.jpg");
        assertThat(playback.path("thumbUrl").asText("")).endsWith("/" + prefix + "thumb.webp");
        assertThat(playback.path("blurhash").asText("")).hasSize(28); // 4×3 components
        assertThat(playback.path("width").asInt()).isEqualTo(1280);
        assertThat(playback.path("height").asInt()).isEqualTo(720);
        assertThat(playback.path("durationMs").asInt()).isBetween(4_900, 5_200);

        // master.m3u8, parsed: three variants, lowest first, each pointing at a playlist that exists.
        Path master = dir.resolve("master-" + mediaId + ".m3u8");
        storage.getToFile(prefix + "master.m3u8", master);
        List<String> lines = Files.readAllLines(master);
        assertThat(lines.getFirst()).isEqualTo("#EXTM3U");
        List<String> resolutions = new ArrayList<>();
        List<String> playlists = new ArrayList<>();
        Pattern resolution = Pattern.compile("RESOLUTION=(\\d+x\\d+)");
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("#EXT-X-STREAM-INF")) {
                Matcher m = resolution.matcher(lines.get(i));
                assertThat(m.find()).isTrue();
                resolutions.add(m.group(1));
                assertThat(lines.get(i)).contains("CODECS=\"avc1.").contains("mp4a.40.2");
                playlists.add(lines.get(i + 1));
            }
        }
        assertThat(resolutions).containsExactly("640x360", "960x540", "1280x720");
        assertThat(playlists).containsExactly("360p/index.m3u8", "540p/index.m3u8", "720p/index.m3u8");

        // Every file a variant playlist names is in storage, with its content type and the immutable
        // cache header.
        for (String playlist : playlists) {
            String folder = prefix + playlist.substring(0, playlist.indexOf('/') + 1);
            assertHead(prefix + playlist, "application/vnd.apple.mpegurl");
            Path variant = dir.resolve(mediaId + "-" + playlist.replace('/', '-'));
            storage.getToFile(prefix + playlist, variant);
            String text = Files.readString(variant);
            assertThat(text).contains("#EXT-X-PLAYLIST-TYPE:VOD").contains("#EXT-X-ENDLIST");
            Matcher init = Pattern.compile("#EXT-X-MAP:URI=\"([^\"]+)\"").matcher(text);
            assertThat(init.find()).isTrue();
            assertHead(folder + init.group(1), "video/mp4");
            List<String> segments =
                    text.lines().filter(line -> line.endsWith(".m4s")).toList();
            assertThat(segments).hasSize(2); // 5 s in 4 s segments
            for (String segment : segments) {
                assertHead(folder + segment, "video/mp4");
            }
        }
        assertHead(prefix + "master.m3u8", "application/vnd.apple.mpegurl");
        assertHead(prefix + "poster.jpg", "image/jpeg");
        assertHead(prefix + "thumb.webp", "image/webp");

        // The database: asset READY with its renditions, one SUCCEEDED job with every stage timed.
        Record asset = asset(mediaId);
        assertThat(asset.get("status")).isEqualTo("READY");
        assertThat(asset.get("attempts")).isEqualTo(1);
        assertThat(asset.get("claimed_by")).isNull();
        assertThat(asset.get("output_prefix")).isEqualTo(prefix);
        JsonNode renditions = json.readTree(asset.get("renditions").toString());
        assertThat(renditions.size()).isEqualTo(3);
        assertThat(renditions.get(2).path("name").asText("")).isEqualTo("720p");
        assertThat(renditions.get(2).path("width").asInt()).isEqualTo(1280);
        assertThat(renditions.get(2).path("bitrate").asInt()).isEqualTo(3_000_000);
        assertThat(renditions.get(2).path("playlist").asText("")).isEqualTo("720p/index.m3u8");
        assertThat(json.readTree(asset.get("probe").toString())
                        .path("videoCodec")
                        .asText(""))
                .isEqualTo("h264");
        List<Record> jobRows = jobRows(mediaId);
        assertThat(jobRows).hasSize(1);
        Record job = jobRows.getFirst();
        assertThat(job.get("status")).isEqualTo("SUCCEEDED");
        assertThat(job.get("attempt")).isEqualTo(1);
        for (String column :
                List.of("queue_wait_ms", "download_ms", "probe_ms", "transcode_ms", "upload_ms", "total_ms")) {
            assertThat(job.get(column)).as(column).isNotNull();
        }
        assertThat(job.get("finished_at")).isNotNull();

        assertThat(events.stream(MediaReady.class).filter(e -> e.mediaId().equals(mediaId)))
                .hasSize(1);
        assertThat(queueDepth()).isZero(); // the message was deleted
        assertThat(Files.exists(Path.of(props.workDir(), mediaId.toString()))).isFalse(); // and the temp files

        // Metrics: a timer per stage, the queue wait, the outcome and the bytes in and out.
        for (String stage : List.of("download", "probe", "transcode", "upload")) {
            assertThat(meters.get("soundcheck.media.transcode.duration")
                            .tag("stage", stage)
                            .timer()
                            .count())
                    .as(stage)
                    .isPositive();
        }
        assertThat(meters.get("soundcheck.media.queue.wait").timer().count()).isPositive();
        assertThat(meters.get("soundcheck.media.jobs")
                        .tag("outcome", "ready")
                        .counter()
                        .count())
                .isPositive();
        assertThat(meters.get("soundcheck.media.bytes")
                        .tag("kind", "original")
                        .summary()
                        .max())
                .isGreaterThanOrEqualTo(clip.length);
        assertThat(meters.get("soundcheck.media.bytes")
                        .tag("kind", "output")
                        .summary()
                        .count())
                .isPositive();

        // The same job delivered again (at-least-once): acknowledged without doing anything.
        queue.send(
                "{\"mediaId\":\"" + mediaId + "\",\"attempt\":1,\"enqueuedAt\":\"" + Instant.now() + "\"}", Map.of());
        assertThat(pollUntilHandled(worker)).isEqualTo(Outcome.DUPLICATE);
        assertThat(queueDepth()).isZero();
        assertThat(jobRows(mediaId)).hasSize(1);
        assertThat(asset(mediaId).get("attempts")).isEqualTo(1);
        assertThat(events.stream(MediaReady.class).filter(e -> e.mediaId().equals(mediaId)))
                .hasSize(1);
    }

    @Test
    void aFileThatIsNotAVideoIsRejectedOnceAndItsMessageDeleted() throws Exception {
        byte[] garbage = new byte[200_000];
        new java.util.Random(7).nextBytes(garbage);
        UUID mediaId = upload(garbage);

        assertThat(pollUntilHandled(worker)).isEqualTo(Outcome.REJECTED);

        Record asset = asset(mediaId);
        assertThat(asset.get("status")).isEqualTo("REJECTED");
        assertThat(asset.get("failure_reason")).isEqualTo("not a readable media file");
        assertThat(asset.get("claimed_by")).isNull();
        List<Record> jobRows = jobRows(mediaId);
        assertThat(jobRows).hasSize(1);
        assertThat(jobRows.getFirst().get("status")).isEqualTo("REJECTED");
        assertThat(queueDepth()).isZero(); // no retry
        assertThat(events.stream(MediaFailed.class).filter(e -> e.mediaId().equals(mediaId)))
                .singleElement()
                .satisfies(e -> assertThat(e.rejected()).isTrue());
        assertThat(api.get("/api/v1/media/" + mediaId, owner)
                        .getBody()
                        .path("status")
                        .asText(""))
                .isEqualTo("REJECTED");
    }

    @Test
    void aJobThatKeepsFailingIsRetriedThenFailedAfterThreeAttemptsAndDeadLettered() throws Exception {
        TranscodeWorker failing = workerWith(new DelegatingTranscoder(realTranscoder) {
            @Override
            public void transcode(Path input, Path outputDir, ProbeResult probe, List<Rung.Sized> ladder) {
                throw new TranscodeException("ffmpeg exited with 1: forced by the test");
            }
        });
        int deadLettersBefore = deadLetters();
        UUID mediaId = upload(clip);

        assertThat(pollUntilHandled(failing)).isEqualTo(Outcome.RETRY);
        Record afterFirst = asset(mediaId);
        assertThat(afterFirst.get("status")).isEqualTo("UPLOADED"); // back in line, lease cleared
        assertThat(afterFirst.get("attempts")).isEqualTo(1);
        assertThat(afterFirst.get("claimed_by")).isNull();
        assertThat(events.stream(MediaFailed.class).filter(e -> e.mediaId().equals(mediaId)))
                .isEmpty();

        assertThat(pollUntilHandled(failing)).isEqualTo(Outcome.RETRY);
        assertThat(pollUntilHandled(failing)).isEqualTo(Outcome.FAILED);

        Record asset = asset(mediaId);
        assertThat(asset.get("status")).isEqualTo("FAILED");
        assertThat(asset.get("attempts")).isEqualTo(3);
        assertThat(asset.get("failure_reason").toString()).contains("forced by the test");
        List<Record> jobRows = jobRows(mediaId);
        assertThat(jobRows).extracting(r -> r.get("status")).containsExactly("FAILED", "FAILED", "FAILED");
        assertThat(jobRows).extracting(r -> r.get("attempt")).containsExactly(1, 2, 3);
        // The stages before the failure were timed; the one that failed and those after it were not.
        assertThat(jobRows.getLast().get("probe_ms")).isNotNull();
        assertThat(jobRows.getLast().get("upload_ms")).isNull();
        assertThat(events.stream(MediaFailed.class).filter(e -> e.mediaId().equals(mediaId)))
                .singleElement()
                .satisfies(e -> assertThat(e.rejected()).isFalse());
        // Nothing was published under media/: a failed attempt leaves no half-ladder behind.
        assertThat(storage.head("media/" + mediaId + "/v3/master.m3u8")).isEmpty();

        // The message has now been received three times: the queue's redrive policy moves it to the
        // dead-letter queue instead of delivering it a fourth time.
        assertThat(failing.pollOnce(Duration.ofSeconds(1))).isEmpty();
        assertThat(deadLetters()).isEqualTo(deadLettersBefore + 1);
    }

    @Test
    void onlyOneOfTwoWorkersClaimsAnAssetAndAnExpiredLeaseCanBeTakenOver() throws Exception {
        UUID mediaId = upload(clip);
        Instant now = clock.instant();

        List<Claim> claims = Parallel.run(2, i -> jobs.claim(mediaId, "worker-" + i, now, props.lease(), null));
        assertThat(claims.stream().filter(Objects::nonNull)).hasSize(1);
        assertThat(asset(mediaId).get("status")).isEqualTo("PROCESSING");

        // The message arrives at a worker while the other one's lease is live: left alone, not deleted.
        QueueMessage message = receiveOne();
        assertThat(worker.handle(message)).isEqualTo(Outcome.SKIPPED);
        assertThat(asset(mediaId).get("attempts")).isEqualTo(1);
        assertThat(sqsCount(IntegrationTest.QUEUE, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
                .isEqualTo(1);

        // A lease that is still being renewed is not expired, however long the job has run.
        Claim held = claims.stream().filter(Objects::nonNull).findFirst().orElseThrow();
        String holder = asset(mediaId).get("claimed_by").toString();
        Instant later = now.plus(props.lease()).plusSeconds(60);
        assertThat(jobs.renewLease(held, holder, later.minusSeconds(120))).isTrue();
        assertThat(jobs.claim(mediaId, "worker-9", later, props.lease(), null)).isNull();

        // Its worker dies (no more renewals): after the lease time another worker takes the asset
        // over, as attempt 2, and the dead attempt's job row is closed.
        Instant muchLater = later.plus(props.lease());
        Claim takeover = jobs.claim(mediaId, "worker-9", muchLater, props.lease(), null);
        assertThat(takeover).isNotNull();
        assertThat(takeover.attempt()).isEqualTo(2);
        assertThat(jobRows(mediaId)).extracting(r -> r.get("status")).containsExactly("FAILED", "RUNNING");
        assertThat(jobRows(mediaId).getFirst().get("error").toString()).contains("lease expired");
        // The first worker, finishing late, cannot overwrite the new owner's work.
        assertThat(jobs.renewLease(held, holder, muchLater)).isFalse();

        queue.delete(message.receiptHandle()); // nobody will finish this asset; leave the queue clean
    }

    @Test
    void shutdownReleasesAJobThatCannotFinishInTimeAndItsMessageIsVisibleAtOnce() throws Exception {
        CountDownLatch transcoding = new CountDownLatch(1);
        CountDownLatch aborted = new CountDownLatch(1);
        TranscodeWorker slow = workerWith(new DelegatingTranscoder(realTranscoder) {
            @Override
            public void transcode(Path input, Path outputDir, ProbeResult probe, List<Rung.Sized> ladder) {
                transcoding.countDown();
                try {
                    aborted.await(); // an ffmpeg that would run for minutes
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new TranscodeException("ffmpeg was killed");
            }

            @Override
            public void abortRunning() {
                aborted.countDown();
            }
        });
        UUID mediaId = upload(clip);

        CompletableFuture<Outcome> outcome = CompletableFuture.supplyAsync(() -> pollUntilHandled(slow));
        assertThat(transcoding.await(30, TimeUnit.SECONDS)).isTrue();
        slow.shutdown(Duration.ofMillis(200));

        assertThat(outcome.get(20, TimeUnit.SECONDS)).isEqualTo(Outcome.RELEASED);
        Record asset = asset(mediaId);
        assertThat(asset.get("status")).isEqualTo("UPLOADED");
        assertThat(asset.get("attempts")).isEqualTo(0); // a released attempt does not count
        assertThat(asset.get("claimed_by")).isNull();
        assertThat(jobRows(mediaId)).extracting(r -> r.get("status")).containsExactly("RELEASED");

        // Visible again immediately (not after the 10-minute visibility timeout), and still a good job:
        // the other worker finishes it.
        assertThat(pollUntilHandled(worker)).isEqualTo(Outcome.READY);
        assertThat(asset(mediaId).get("attempts")).isEqualTo(1);
        assertThat(jobRows(mediaId)).extracting(r -> r.get("status")).containsExactly("RELEASED", "SUCCEEDED");
    }

    @Test
    void theWorkerContinuesTheTraceThatArrivedWithTheMessage() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String parentSpanId = "00f067aa0ba902b7";
        List<String> seen = new ArrayList<>();
        TranscodeWorker tracing = workerWith(new DelegatingTranscoder(realTranscoder) {
            @Override
            public ProbeResult probe(Path input) {
                seen.add(traceparent.current().orElse("no trace"));
                throw new MediaRejectedException("stopped by the test"); // the quickest way out
            }
        });
        upload(clip);
        QueueMessage relayed = receiveOne();
        QueueMessage message = new QueueMessage(
                relayed.messageId(),
                relayed.receiptHandle(),
                relayed.body(),
                Map.of("traceparent", "00-" + traceId + "-" + parentSpanId + "-01"),
                relayed.receiveCount());

        assertThat(tracing.handle(message)).isEqualTo(Outcome.REJECTED);

        // Inside a stage the current span belongs to the same trace, and is a new span of its own.
        assertThat(seen).singleElement().satisfies(current -> {
            assertThat(current).startsWith("00-" + traceId + "-");
            assertThat(current).doesNotContain(parentSpanId);
        });
    }

    @Test
    void aMessageThatIsNotATranscodeJobIsDeleted() {
        queue.send("{\"hello\":\"world\"}", Map.of());
        assertThat(pollUntilHandled(worker)).isEqualTo(Outcome.MALFORMED);
        queue.send("not json", Map.of());
        assertThat(pollUntilHandled(worker)).isEqualTo(Outcome.MALFORMED);
        assertThat(queueDepth()).isZero();
    }

    @Test
    void aStoppedWorkerHandsMessagesBackAndARestartedOneWorksAgain() {
        TranscodeWorker second = workerWith(realTranscoder);
        second.start(); // loops are off in tests; this only arms it
        second.stop();
        queue.send("{\"hello\":\"world\"}", Map.of());

        // Stopped: whatever it receives becomes visible again at once, unprocessed.
        assertThat(second.pollOnce(Duration.ofSeconds(2))).isEmpty();
        assertThat(queueDepth()).isEqualTo(1);

        second.start();
        assertThat(pollUntilHandled(second)).isEqualTo(Outcome.MALFORMED);
        assertThat(queueDepth()).isZero();
    }

    @Test
    void contentTypesFollowTheFileExtension() {
        assertThat(TranscodeWorker.contentType("master.m3u8")).isEqualTo("application/vnd.apple.mpegurl");
        assertThat(TranscodeWorker.contentType("720p/seg_000.m4s")).isEqualTo("video/mp4");
        assertThat(TranscodeWorker.contentType("720p/init_2.mp4")).isEqualTo("video/mp4");
        assertThat(TranscodeWorker.contentType("poster.jpg")).isEqualTo("image/jpeg");
        assertThat(TranscodeWorker.contentType("thumb.webp")).isEqualTo("image/webp");
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** A second worker around a transcoder of the test's choosing; everything else is the real thing. */
    private TranscodeWorker workerWith(Transcoder transcoder) {
        return new TranscodeWorker(queue, storage, jobs, transcoder, props, json, clock, meters, spans);
    }

    /** Uploads through the API as a client would: ticket, PUT to the presigned URL, complete. */
    private UUID upload(byte[] bytes) throws Exception {
        ResponseEntity<JsonNode> created = api.post(
                "/api/v1/media/uploads",
                Map.of("kind", "VIDEO", "contentType", "video/mp4", "bytes", bytes.length, "durationMs", 5_000),
                owner);
        assertThat(created.getStatusCode())
                .as(String.valueOf(created.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        JsonNode ticket = created.getBody();
        assertThat(ticket.path("strategy").asText("")).isEqualTo("SINGLE");
        HttpResponse<Void> put = HTTP.send(
                HttpRequest.newBuilder(URI.create(ticket.path("putUrl").asText("")))
                        .header("Content-Type", "video/mp4")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(put.statusCode()).isEqualTo(200);
        String mediaId = ticket.path("mediaId").asText("");
        assertThat(api.post("/api/v1/media/uploads/" + mediaId + "/complete", null, owner)
                        .getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        return UUID.fromString(mediaId);
    }

    /** Polls until the worker has handled one message (the relay sends it just after the commit). */
    private static Outcome pollUntilHandled(TranscodeWorker worker) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            List<Outcome> outcomes = worker.pollOnce(Duration.ofSeconds(1));
            if (!outcomes.isEmpty()) {
                return outcomes.getFirst();
            }
        }
        throw new AssertionError("no message arrived within 30 s");
    }

    private QueueMessage receiveOne() {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            List<QueueMessage> messages = queue.receive(1, Duration.ofSeconds(1));
            if (!messages.isEmpty()) {
                return messages.getFirst();
            }
        }
        throw new AssertionError("no message arrived within 30 s");
    }

    private void assertHead(String key, String contentType) {
        HeadObjectResponse head =
                s3.headObject(b -> b.bucket(IntegrationTest.BUCKET).key(key));
        assertThat(head.contentType()).as(key).isEqualTo(contentType);
        assertThat(head.cacheControl()).as(key).isEqualTo("public, max-age=31536000, immutable");
        assertThat(head.contentLength()).as(key).isPositive();
    }

    private Record asset(UUID mediaId) {
        return dsl.fetchSingle("select * from media.media_asset where id = ?", mediaId);
    }

    private List<Record> jobRows(UUID mediaId) {
        return new ArrayList<>(
                dsl.fetch("select * from media.transcode_job where media_id = ? order by attempt", mediaId));
    }

    /** Messages in the transcode queue, visible or in flight. */
    private int queueDepth() {
        JobQueue.QueueAttributes attributes = queue.attributes();
        return attributes.visible() + attributes.inFlight() + attributes.delayed();
    }

    private int deadLetters() {
        return sqsCount(IntegrationTest.DLQ, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
    }

    private int sqsCount(String queueName, QueueAttributeName attribute) {
        String url = sqs.getQueueUrl(b -> b.queueName(queueName)).queueUrl();
        return Integer.parseInt(sqs.getQueueAttributes(b -> b.queueUrl(url).attributeNames(attribute))
                .attributes()
                .get(attribute));
    }

    /** Passes everything to the real transcoder; tests override the one method they want to break. */
    private static class DelegatingTranscoder implements Transcoder {
        private final Transcoder delegate;

        DelegatingTranscoder(Transcoder delegate) {
            this.delegate = delegate;
        }

        @Override
        public ProbeResult probe(Path input) {
            return delegate.probe(input);
        }

        @Override
        public void transcode(Path input, Path outputDir, ProbeResult probe, List<Rung.Sized> ladder) {
            delegate.transcode(input, outputDir, probe, ladder);
        }

        @Override
        public void poster(Path input, Path output, ProbeResult probe) {
            delegate.poster(input, output, probe);
        }

        @Override
        public void thumb(Path input, Path output, ProbeResult probe) {
            delegate.thumb(input, output, probe);
        }

        @Override
        public void abortRunning() {
            delegate.abortRunning();
        }
    }
}
