package app.soundcheck.media.internal.aws;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.media.JobQueue;
import app.soundcheck.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;

/**
 * Round-trips the {@link JobQueue} port against LocalStack SQS. It uses a queue of its
 * own: the application's transcode queue receives real messages from the outbox relay
 * whenever another test completes an upload, so exact counts can only be asserted on a private queue.
 */
class SqsJobQueueIT extends IntegrationTest {

    @Autowired
    SqsClient sqs;

    @Test
    void queueSendReceiveVisibilityDelete() {
        String url = sqs.createQueue(CreateQueueRequest.builder()
                        .queueName("ports-test-" + UUID.randomUUID())
                        .build())
                .queueUrl();
        JobQueue queue = new SqsJobQueue(sqs, new QueueProperties(url));

        String messageId = queue.send("{\"mediaId\":\"x\"}", Map.of("traceparent", "00-abc-def-01"));
        assertThat(messageId).isNotBlank();

        List<JobQueue.QueueMessage> received = queue.receive(1, Duration.ofSeconds(5));
        assertThat(received).hasSize(1);
        JobQueue.QueueMessage message = received.getFirst();
        assertThat(message.body()).isEqualTo("{\"mediaId\":\"x\"}");
        assertThat(message.attributes()).containsEntry("traceparent", "00-abc-def-01");
        assertThat(message.receiveCount()).isEqualTo(1);
        assertThat(queue.attributes().inFlight()).isEqualTo(1);

        // Release the lease immediately (what a worker does on SIGTERM) → received again, count 2.
        queue.changeVisibility(message.receiptHandle(), Duration.ZERO);
        JobQueue.QueueMessage again = queue.receive(1, Duration.ofSeconds(5)).getFirst();
        assertThat(again.messageId()).isEqualTo(messageId);
        assertThat(again.receiveCount()).isEqualTo(2);

        queue.delete(again.receiptHandle());
        assertThat(queue.receive(1, Duration.ofSeconds(1))).isEmpty();
        assertThat(queue.attributes()).isEqualTo(new JobQueue.QueueAttributes(0, 0, 0));
    }
}
