package app.soundcheck.media.internal;

import app.soundcheck.media.JobQueue;
import app.soundcheck.media.MediaUploaded;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Outbox relay: turns a committed {@link MediaUploaded} into a transcode job on the
 * queue. {@code @ApplicationModuleListener} runs this after the publishing transaction commits, on
 * another thread, and marks the stored publication complete only if the method returns normally. If
 * {@code send} throws, the publication stays incomplete and {@code OutboxResubmitter} tries again, so a
 * committed upload is never lost and an uncommitted one is never queued. A job may be queued twice
 * (a crash between the send and the completion mark); the worker's conditional claim makes that harmless.
 */
@Component
@ConditionalOnRole(Roles.API)
class TranscodeRelay {

    /** Body of a transcode message. */
    record TranscodeJob(UUID mediaId, int attempt, Instant enqueuedAt) {}

    private final JobQueue queue;
    private final MediaAssetRepository assets;
    private final JsonMapper json;
    private final Clock clock;

    TranscodeRelay(JobQueue queue, MediaAssetRepository assets, JsonMapper json, Clock clock) {
        this.queue = queue;
        this.assets = assets;
        this.json = json;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(MediaUploaded event) {
        int attempt = assets.find(event.mediaId()).map(a -> a.attempts() + 1).orElse(1);
        String body;
        try {
            body = json.writeValueAsString(new TranscodeJob(event.mediaId(), attempt, Instant.now(clock)));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        // The trace context rides in the event (it was stored with it), then in a message attribute, so
        // the worker's spans join the trace of the request that completed the upload.
        queue.send(body, event.traceparent() == null ? Map.of() : Map.of("traceparent", event.traceparent()));
    }
}
