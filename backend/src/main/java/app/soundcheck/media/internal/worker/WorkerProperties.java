package app.soundcheck.media.internal.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code soundcheck.worker.*}. */
@ConfigurationProperties(prefix = "soundcheck.worker")
public record WorkerProperties(
        /** False keeps the polling loops from starting (tests drive the worker one poll at a time). */
        @DefaultValue("true") boolean enabled,
        /** Jobs processed at once; FFmpeg uses the cores, so 1 per 2 vCPUs. */
        @DefaultValue("1") int concurrency,
        /** SQS long-poll wait. */
        @DefaultValue("20s") Duration pollWait,
        /** While a job runs, its message is kept invisible for this long, renewed at {@code heartbeat} intervals. */
        @DefaultValue("10m") Duration visibility,
        @DefaultValue("2m") Duration heartbeat,
        /** A PROCESSING asset whose claim is older than this is assumed abandoned and may be claimed again. */
        @DefaultValue("15m") Duration lease,
        /** After this many attempts the asset is FAILED. */
        @DefaultValue("3") int maxAttempts,
        /** How soon a failed job's message becomes visible again. */
        @DefaultValue("30s") Duration retryDelay,
        /** On shutdown a running job gets this long to finish before it is released. */
        @DefaultValue("90s") Duration shutdownGrace,
        @DefaultValue("/tmp/soundcheck") String workDir) {}
