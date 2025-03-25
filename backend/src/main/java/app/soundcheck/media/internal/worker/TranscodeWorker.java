package app.soundcheck.media.internal.worker;

import app.soundcheck.media.JobQueue;
import app.soundcheck.media.JobQueue.QueueMessage;
import app.soundcheck.media.ObjectStorage;
import app.soundcheck.media.internal.transcode.BlurHash;
import app.soundcheck.media.internal.transcode.MediaRejectedException;
import app.soundcheck.media.internal.transcode.ProbeResult;
import app.soundcheck.media.internal.transcode.Rung;
import app.soundcheck.media.internal.worker.TranscodeJobs.Claim;
import app.soundcheck.media.internal.worker.TranscodeJobs.Outputs;
import app.soundcheck.media.internal.worker.TranscodeJobs.Timings;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.observability.Spans;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The transcode worker: takes one message at a time from the queue, claims the asset,
 * turns the uploaded file into an HLS ladder with a poster, a thumbnail and a BlurHash, uploads the
 * results and marks the asset READY.
 *
 * <p>The queue delivers a message at least once, so every step assumes it may be running for the second
 * time: the claim is a conditional UPDATE (only one worker gets the row), outputs go under a
 * per-attempt prefix (a retry never overwrites what a player may be reading), and the message is
 * deleted only after the database says READY.
 */
@Component
@ConditionalOnRole(Roles.WORKER)
public class TranscodeWorker implements SmartLifecycle, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TranscodeWorker.class);
    private static final String IMMUTABLE = "public, max-age=31536000, immutable";
    private static final String METRIC_DURATION = "soundcheck.media.transcode.duration";

    /** What happened to one received message. */
    public enum Outcome {
        READY,
        REJECTED,
        RETRY,
        FAILED,
        RELEASED,
        /** The asset was already finished (or gone): a redelivered or duplicated message, acknowledged. */
        DUPLICATE,
        /** Another worker holds a live lease, or the asset is FAILED: the message is left alone. */
        SKIPPED,
        /** Not a transcode message at all: deleted. */
        MALFORMED,
        /** This worker lost its lease while working; whoever took over owns the asset now. */
        LEASE_LOST
    }

    /** Body of a transcode message, as written by {@code TranscodeRelay}. */
    private record Job(UUID mediaId, @Nullable Instant enqueuedAt) {}

    private final JobQueue queue;
    private final ObjectStorage storage;
    private final TranscodeJobs jobs;
    private final Transcoder transcoder;
    private final WorkerProperties props;
    private final JsonMapper json;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Spans spans;

    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + "-"
            + UUID.randomUUID().toString().substring(0, 8);
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("transcode-heartbeat").daemon().factory());
    private final Set<Thread> busy = ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private volatile boolean stopping;
    private volatile boolean releasing;

    TranscodeWorker(
            JobQueue queue,
            ObjectStorage storage,
            TranscodeJobs jobs,
            Transcoder transcoder,
            WorkerProperties props,
            JsonMapper json,
            Clock clock,
            MeterRegistry meters,
            Spans spans) {
        this.queue = queue;
        this.storage = storage;
        this.jobs = jobs;
        this.transcoder = transcoder;
        this.props = props;
        this.json = json;
        this.clock = clock;
        this.meters = meters;
        this.spans = spans;
    }

    // ---- polling ----------------------------------------------------------------------------------

    /**
     * One long poll: waits up to {@code wait} for a message and processes it on the calling thread.
     * Returns what happened to each message received (empty when the queue had nothing).
     */
    public List<Outcome> pollOnce(Duration wait) {
        List<Outcome> outcomes = new ArrayList<>();
        for (QueueMessage message : queue.receive(1, wait)) {
            if (stopping) {
                // Received while shutting down: hand it straight back for another worker.
                queue.changeVisibility(message.receiptHandle(), Duration.ZERO);
                continue;
            }
            outcomes.add(handle(message));
        }
        return outcomes;
    }

    private void loop() {
        while (!stopping) {
            try {
                pollOnce(props.pollWait());
            } catch (RuntimeException e) {
                if (stopping) {
                    break;
                }
                // The queue or the database is unreachable: don't spin, try again shortly.
                log.warn("transcode poll failed, retrying in 5 s: {}", e.toString());
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException interrupted) {
                    break;
                }
            }
        }
    }

    // ---- one message ------------------------------------------------------------------------------

    Outcome handle(QueueMessage message) {
        Job job = parse(message.body());
        if (job == null) {
            log.warn("deleting a message that is not a transcode job (message {})", message.messageId());
            queue.delete(message.receiptHandle());
            return count(Outcome.MALFORMED);
        }
        try (Spans.Scope span = spans.continued(message.attributes().get("traceparent"), "media.transcode")) {
            span.tag("media.id", job.mediaId().toString());
            Outcome outcome = process(job, message, span);
            span.tag("outcome", outcome.name());
            return count(outcome);
        }
    }

    private Outcome process(Job job, QueueMessage message, Spans.Scope span) {
        Instant claimedAt = clock.instant();
        Integer queueWaitMs = job.enqueuedAt() == null
                ? null
                : (int) Math.max(
                        0, Duration.between(job.enqueuedAt(), claimedAt).toMillis());
        Claim claim = jobs.claim(job.mediaId(), workerId, claimedAt, props.lease(), queueWaitMs);
        if (claim == null) {
            return notClaimed(job.mediaId(), message);
        }
        if (queueWaitMs != null) {
            Timer.builder("soundcheck.media.queue.wait").register(meters).record(Duration.ofMillis(queueWaitMs));
        }
        span.tag("attempt", String.valueOf(claim.attempt()));
        log.info("transcode started: media {} attempt {}", claim.mediaId(), claim.attempt());

        // The message stays invisible to other workers while this one works, renewed by the heartbeat,
        // which also pushes the database lease forward.
        queue.changeVisibility(message.receiptHandle(), props.visibility());
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(
                () -> beat(claim, message),
                props.heartbeat().toMillis(),
                props.heartbeat().toMillis(),
                TimeUnit.MILLISECONDS);

        Stopwatch watch = new Stopwatch();
        Path workDir = Path.of(props.workDir(), claim.mediaId().toString());
        busy.add(Thread.currentThread());
        try {
            Outputs outputs = produce(claim, workDir, watch);
            if (!jobs.finalizeReady(claim, workerId, outputs, watch.timings(), clock.instant())) {
                log.warn(
                        "transcode finished but the lease was lost: media {} attempt {}",
                        claim.mediaId(),
                        claim.attempt());
                return Outcome.LEASE_LOST;
            }
            queue.delete(message.receiptHandle());
            log.info(
                    "transcode ready: media {} attempt {} in {} ms",
                    claim.mediaId(),
                    claim.attempt(),
                    watch.timings().totalMs());
            return Outcome.READY;
        } catch (MediaRejectedException e) {
            jobs.reject(claim, e.getMessage(), watch.timings(), clock.instant());
            queue.delete(message.receiptHandle());
            log.info("transcode rejected: media {}: {}", claim.mediaId(), e.getMessage());
            return Outcome.REJECTED;
        } catch (RuntimeException e) {
            if (releasing) {
                jobs.release(claim, watch.timings(), clock.instant());
                queue.changeVisibility(message.receiptHandle(), Duration.ZERO);
                log.info("transcode released on shutdown: media {}", claim.mediaId());
                return Outcome.RELEASED;
            }
            span.error(e);
            boolean terminal = jobs.fail(claim, describe(e), props.maxAttempts(), watch.timings(), clock.instant());
            // Not deleted: the queue redelivers it. After the last attempt the next delivery exceeds the
            // queue's receive limit, which moves the message to the dead-letter queue.
            queue.changeVisibility(message.receiptHandle(), terminal ? Duration.ZERO : props.retryDelay());
            log.warn(
                    "transcode failed: media {} attempt {} of {}: {}",
                    claim.mediaId(),
                    claim.attempt(),
                    props.maxAttempts(),
                    describe(e));
            return terminal ? Outcome.FAILED : Outcome.RETRY;
        } finally {
            busy.remove(Thread.currentThread());
            heartbeat.cancel(false);
            deleteRecursively(workDir);
        }
    }

    /** The claim returned nothing: decide from the asset's status what the message is. */
    private Outcome notClaimed(UUID mediaId, QueueMessage message) {
        String status = jobs.status(mediaId);
        if (status == null || status.equals("READY") || status.equals("REJECTED") || status.equals("DELETED")) {
            queue.delete(message.receiptHandle());
            return Outcome.DUPLICATE;
        }
        // PROCESSING under someone else's live lease: the message reappears after its visibility
        // timeout, by which time the other worker has finished or its lease has expired.
        // FAILED: left for the dead-letter queue.
        return Outcome.SKIPPED;
    }

    private void beat(Claim claim, QueueMessage message) {
        try {
            queue.changeVisibility(message.receiptHandle(), props.visibility());
            jobs.renewLease(claim, workerId, clock.instant());
        } catch (RuntimeException e) {
            log.warn("heartbeat failed for media {}: {}", claim.mediaId(), e.toString());
        }
    }

    // ---- the stages -------------------------------------------------------------------------------

    private Outputs produce(Claim claim, Path workDir, Stopwatch watch) {
        deleteRecursively(workDir);
        Path input = workDir.resolve("original");
        Path outputDir = workDir.resolve("out");
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        watch.downloadMs = stage("download", () -> {
            storage.getToFile(claim.originalKey(), input);
            return null;
        });
        long uploadedBytes = size(input);
        recordInputBytes(claim, uploadedBytes);

        ProbeResult[] probe = new ProbeResult[1];
        watch.probeMs = stage("probe", () -> probe[0] = transcoder.probe(input));
        List<Rung.Sized> ladder = Rung.ladderFor(probe[0]);

        Path poster = outputDir.resolve("poster.jpg");
        Path thumb = outputDir.resolve("thumb.webp");
        String[] blurhash = new String[1];
        watch.transcodeMs = stage("transcode", () -> {
            transcoder.transcode(input, outputDir, probe[0], ladder);
            transcoder.poster(input, poster, probe[0]);
            transcoder.thumb(input, thumb, probe[0]);
            blurhash[0] = blurhash(poster);
            return null;
        });

        String prefix = "media/" + claim.mediaId() + "/v" + claim.attempt() + "/";
        watch.uploadMs = stage("upload", () -> {
            upload(outputDir, prefix);
            return null;
        });

        List<Map<String, Object>> renditions = ladder.stream()
                .map(sized -> Map.<String, Object>of(
                        "name",
                        sized.rung().label,
                        "width",
                        sized.width(),
                        "height",
                        sized.height(),
                        "bitrate",
                        sized.rung().bitrateK * 1000,
                        "playlist",
                        sized.rung().label + "/index.m3u8"))
                .toList();
        String renditionsJson;
        String probeJson;
        try {
            renditionsJson = json.writeValueAsString(renditions);
            probeJson = json.writeValueAsString(probe[0]);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        return new Outputs(
                prefix,
                renditionsJson,
                probeJson,
                prefix + "poster.jpg",
                prefix + "thumb.webp",
                blurhash[0],
                (int) probe[0].durationMs(),
                probe[0].displayWidth(),
                probe[0].displayHeight());
    }

    /** Runs one stage inside its own span and timer; returns how long it took in milliseconds. */
    private int stage(String name, Supplier<?> work) {
        if (releasing) {
            throw new IllegalStateException("worker is shutting down");
        }
        long start = System.nanoTime();
        try (Spans.Scope span = spans.child("transcode." + name)) {
            try {
                work.get();
            } catch (RuntimeException e) {
                span.error(e);
                throw e;
            }
        } finally {
            Timer.builder(METRIC_DURATION)
                    .tag("stage", name)
                    .register(meters)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
        return (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    /**
     * Uploads everything under {@code outputDir} with its content type and an immutable cache header
     * (the prefix contains the attempt number, so a path's content never changes). The master playlist
     * goes last: whoever can see it can already fetch everything it points to.
     */
    private void upload(Path outputDir, String prefix) {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(outputDir)) {
            files = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(
                                    (Path p) -> p.getFileName().toString().equals("master.m3u8"))
                            .thenComparing(Comparator.naturalOrder()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        long bytes = 0;
        for (Path file : files) {
            if (releasing) {
                throw new IllegalStateException("worker is shutting down");
            }
            String relative = outputDir.relativize(file).toString().replace('\\', '/');
            storage.putFile(prefix + relative, file, contentType(relative), IMMUTABLE);
            bytes += size(file);
        }
        bytes("output").record(bytes);
    }

    static String contentType(String name) {
        String extension = name.substring(name.lastIndexOf('.') + 1);
        return switch (extension) {
            case "m3u8" -> "application/vnd.apple.mpegurl";
            case "m4s", "mp4" -> "video/mp4";
            case "jpg" -> "image/jpeg";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }

    /**
     * 4×3 components from the poster frame. The JDK cannot decode WebP, so the JPEG poster (the same
     * frame as the thumbnail) is read instead, shrunk first: BlurHash keeps only the lowest frequencies.
     */
    private static String blurhash(Path poster) {
        try {
            BufferedImage image = ImageIO.read(poster.toFile());
            if (image == null) {
                throw new IllegalStateException("the poster could not be decoded");
            }
            int width = Math.max(1, Math.min(64, image.getWidth()));
            int height = Math.max(1, Math.round(image.getHeight() * (width / (float) image.getWidth())));
            BufferedImage small = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var graphics = small.createGraphics();
            graphics.drawImage(image.getScaledInstance(width, height, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
            graphics.dispose();
            return BlurHash.encode(small, 4, 3);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- metrics ----------------------------------------------------------------------------------

    private Outcome count(Outcome outcome) {
        meters.counter("soundcheck.media.jobs", "outcome", outcome.name().toLowerCase())
                .increment();
        return outcome;
    }

    private DistributionSummary bytes(String kind) {
        return DistributionSummary.builder("soundcheck.media.bytes")
                .baseUnit("bytes")
                .tag("kind", kind)
                .register(meters);
    }

    /**
     * {@code kind=original} is the file as recorded; when the phone compressed it before uploading (it
     * says so in the upload's client metadata), the uploaded size is also recorded as {@code compressed}.
     */
    private void recordInputBytes(Claim claim, long uploadedBytes) {
        long preCompression = 0;
        try {
            JsonNode original = json.readTree(claim.clientMetaJson()).path("originalBytes");
            preCompression = original.isNumber() ? original.asLong() : 0;
        } catch (JacksonException e) {
            // Client metadata is free-form: unreadable means "not reported".
        }
        if (preCompression > 0) {
            bytes("original").record(preCompression);
            bytes("compressed").record(uploadedBytes);
        } else {
            bytes("original").record(uploadedBytes);
        }
    }

    // ---- lifecycle --------------------------------------------------------------------------------

    @Override
    public void start() {
        // A stopped worker can be started again (Spring's test framework pauses and resumes contexts).
        stopping = false;
        releasing = false;
        running = true;
        if (!props.enabled()) {
            return;
        }
        for (int i = 0; i < props.concurrency(); i++) {
            Thread.ofVirtual().name("transcode-worker-" + i).start(this::loop);
        }
        log.info("transcode worker {} started with concurrency {}", workerId, props.concurrency());
    }

    @Override
    public void stop() {
        shutdown(props.shutdownGrace());
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Graceful shutdown: no new messages; a running job gets {@code grace} to finish; after
     * that FFmpeg is killed, the job is marked RELEASED and its message made visible again at once, so
     * another worker picks it up without waiting for the visibility timeout.
     *
     * <p>Threads are not interrupted: on a virtual thread an interrupt closes whatever socket the thread
     * is blocked on, which could be the database connection of the release itself. Killing FFmpeg ends
     * the long stage; the short ones (download, upload) notice {@code releasing} between steps. A loop
     * idle in a long poll simply ends with the process.
     */
    void shutdown(Duration grace) {
        stopping = true;
        if (!awaitIdle(grace)) {
            releasing = true;
            transcoder.abortRunning();
            if (!awaitIdle(Duration.ofSeconds(15))) {
                log.warn("transcode worker stopped with a job still running; its lease will expire");
            }
        }
    }

    @Override
    public void destroy() {
        heartbeats.shutdownNow();
    }

    private boolean awaitIdle(Duration limit) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (!busy.isEmpty()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private @Nullable Job parse(String body) {
        try {
            Job job = json.readValue(body, Job.class);
            return job == null || job.mediaId() == null ? null : job;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("could not delete {}: {}", path, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("could not clean {}: {}", dir, e.toString());
        }
    }

    /** Stage timings of the job in hand; a stage that was not reached stays null. */
    private static final class Stopwatch {
        private final long start = System.nanoTime();
        private @Nullable Integer downloadMs;
        private @Nullable Integer probeMs;
        private @Nullable Integer transcodeMs;
        private @Nullable Integer uploadMs;

        Timings timings() {
            return new Timings(downloadMs, probeMs, transcodeMs, uploadMs, (int)
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }
}
