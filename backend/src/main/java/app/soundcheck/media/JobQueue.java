package app.soundcheck.media;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Job queue port: the outbox relay sends transcode jobs; workers long-poll, process
 * and delete them. At-least-once delivery, so consumers are idempotent. Implemented by {@code SqsJobQueue}.
 */
public interface JobQueue {

    /** Sends a message with string attributes (e.g. W3C {@code traceparent}); returns the message id. */
    String send(String body, Map<String, String> attributes);

    /** Long-polls for up to {@code maxMessages} messages, waiting at most {@code wait}. */
    List<QueueMessage> receive(int maxMessages, Duration wait);

    void delete(String receiptHandle);

    /** Makes a message visible again after {@code timeout} (zero = immediately). */
    void changeVisibility(String receiptHandle, Duration timeout);

    /** Approximate counts for monitoring and autoscaling. */
    QueueAttributes attributes();

    /** A received message; {@code receiveCount} starts at 1 and drives the DLQ redrive. */
    record QueueMessage(
            String messageId, String receiptHandle, String body, Map<String, String> attributes, int receiveCount) {}

    /** Queue depth snapshot. */
    record QueueAttributes(int visible, int inFlight, int delayed) {}
}
