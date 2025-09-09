package app.soundcheck.media.internal.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.media.JobQueue;
import app.soundcheck.support.IntegrationTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
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
        JobQueue queue = new SqsJobQueue(sqs, new QueueProperties(url, null));

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
        assertThat(queue.deadLetterAttributes()).as("no DLQ configured").isEmpty();
    }

    /** The console reads the dead-letter queue's depth next to the queue's. */
    @Test
    void deadLetterQueueCountsAreReadFromTheConfiguredDlq() {
        String name = "dlq-test-" + UUID.randomUUID();
        String url = sqs.createQueue(b -> b.queueName(name)).queueUrl();
        String dlqUrl = sqs.createQueue(b -> b.queueName(name + "-dlq")).queueUrl();
        JobQueue queue = new SqsJobQueue(sqs, new QueueProperties(url, dlqUrl));
        sqs.sendMessage(b -> b.queueUrl(dlqUrl).messageBody("{\"mediaId\":\"dead\"}"));
        sqs.sendMessage(b -> b.queueUrl(dlqUrl).messageBody("{\"mediaId\":\"dead-too\"}"));

        assertThat(queue.deadLetterAttributes()).contains(new JobQueue.QueueAttributes(2, 0, 0));
        assertThat(queue.attributes()).isEqualTo(new JobQueue.QueueAttributes(0, 0, 0));
        // A blank setting (SQS_DLQ_URL=) means no DLQ, not a call to an empty URL.
        assertThat(new SqsJobQueue(sqs, new QueueProperties(url, " ")).deadLetterAttributes())
                .isEmpty();
    }

    /**
     * SQS down: the counts fail fast with an exception (which the console turns into null counts and
     * "unreachable"), instead of waiting out the client's 30 s call timeout.
     */
    @Test
    void anUnreachableQueueFailsFast() {
        try (SqsClient nowhere = SqsClient.builder()
                .endpointOverride(URI.create("http://127.0.0.1:" + closedPort()))
                .region(Region.EU_WEST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            String url = "http://127.0.0.1:1/000000000000/nowhere";
            JobQueue queue = new SqsJobQueue(nowhere, new QueueProperties(url, url + "-dlq"));
            long start = System.nanoTime();
            assertThatThrownBy(queue::attributes).isInstanceOf(SdkException.class);
            assertThatThrownBy(queue::deadLetterAttributes).isInstanceOf(SdkException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .isLessThan(SqsJobQueue.ATTRIBUTES_TIMEOUT.multipliedBy(2).plusSeconds(2));
        }
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
