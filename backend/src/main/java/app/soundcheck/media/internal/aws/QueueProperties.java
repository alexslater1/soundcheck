package app.soundcheck.media.internal.aws;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code soundcheck.queue.*} ({@code SQS_QUEUE_URL}). */
@ConfigurationProperties(prefix = "soundcheck.queue")
public record QueueProperties(String url) {}
