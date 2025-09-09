package app.soundcheck.media.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.media.JobQueue;
import app.soundcheck.media.internal.MediaAdmin.PipelineDlqDepth;
import app.soundcheck.media.internal.MediaAdmin.PipelineQueueDepth;
import app.soundcheck.media.internal.MediaAdmin.QueueSnapshot;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The console's queue tiles never turn an SQS outage into a 500. */
class MediaAdminQueueSnapshotTest {

    @Test
    void readsTheQueueAndTheDeadLetterQueue() {
        QueueSnapshot snapshot = MediaAdmin.queueSnapshot(queue(
                () -> new JobQueue.QueueAttributes(4, 1, 2), () -> Optional.of(new JobQueue.QueueAttributes(3, 0, 0))));
        assertThat(snapshot)
                .isEqualTo(new QueueSnapshot(new PipelineQueueDepth(4, 1, 2), new PipelineDlqDepth(3), null));
    }

    @Test
    void noDeadLetterQueueConfiguredLeavesOnlyItsTileEmpty() {
        QueueSnapshot snapshot =
                MediaAdmin.queueSnapshot(queue(() -> new JobQueue.QueueAttributes(0, 0, 0), Optional::empty));
        assertThat(snapshot).isEqualTo(new QueueSnapshot(new PipelineQueueDepth(0, 0, 0), null, null));
    }

    @Test
    void anUnreachableQueueGivesNullCountsAndSaysSo() {
        QueueSnapshot snapshot = MediaAdmin.queueSnapshot(queue(
                () -> {
                    throw new IllegalStateException("connection refused");
                },
                Optional::empty));
        assertThat(snapshot).isEqualTo(new QueueSnapshot(null, null, "unreachable"));

        QueueSnapshot dlqDown = MediaAdmin.queueSnapshot(queue(() -> new JobQueue.QueueAttributes(1, 0, 0), () -> {
            throw new IllegalStateException("timed out");
        }));
        assertThat(dlqDown).isEqualTo(new QueueSnapshot(null, null, "unreachable"));
    }

    private static JobQueue queue(
            java.util.function.Supplier<JobQueue.QueueAttributes> main,
            java.util.function.Supplier<Optional<JobQueue.QueueAttributes>> dlq) {
        return new JobQueue() {
            @Override
            public String send(String body, Map<String, String> attributes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<QueueMessage> receive(int maxMessages, Duration wait) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void delete(String receiptHandle) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void changeVisibility(String receiptHandle, Duration timeout) {
                throw new UnsupportedOperationException();
            }

            @Override
            public QueueAttributes attributes() {
                return main.get();
            }

            @Override
            public Optional<QueueAttributes> deadLetterAttributes() {
                return dlq.get();
            }
        };
    }
}
