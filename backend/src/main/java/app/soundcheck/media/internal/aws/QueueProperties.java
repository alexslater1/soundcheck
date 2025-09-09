package app.soundcheck.media.internal.aws;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code soundcheck.queue.*} ({@code SQS_QUEUE_URL}, {@code SQS_DLQ_URL}).
 *
 * @param url the transcode queue
 * @param dlqUrl its dead-letter queue, watched by the pipeline console and never read
 *     from; blank or absent turns the console's DLQ tile off
 */
@ConfigurationProperties(prefix = "soundcheck.queue")
public record QueueProperties(String url, @Nullable String dlqUrl) {

    /** The dead-letter queue's URL, when one is configured. */
    public Optional<String> deadLetterUrl() {
        return dlqUrl == null || dlqUrl.isBlank() ? Optional.empty() : Optional.of(dlqUrl);
    }
}
