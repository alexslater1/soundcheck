package app.soundcheck.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox relay: a rolled-back transaction queues nothing; when the queue is
 * unreachable the publication stays incomplete, and a resubmission then delivers exactly one message.
 * The queue is the real LocalStack queue behind a Mockito spy, which is how "SQS is down" is simulated.
 */
class TranscodeRelayIT extends IntegrationTest {

    @MockitoSpyBean
    JobQueue queue;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    IncompleteEventPublications incomplete;

    @Autowired
    DSLContext dsl;

    @Autowired
    JsonMapper json;

    @Autowired
    TestRestTemplate rest;

    @Test
    void aRolledBackTransactionQueuesNothing() throws Exception {
        UUID mediaId = UUID.randomUUID();
        UUID owner = new TestApi(rest).register("relay").id();

        tx.executeWithoutResult(status -> {
            events.publishEvent(new MediaUploaded(mediaId, owner, Instant.now(), null));
            status.setRollbackOnly();
        });

        // The listener only runs after a commit; give an (incorrect) asynchronous delivery time to show up.
        Thread.sleep(1500);
        verify(queue, never()).send(contains(mediaId.toString()), any());
        assertThat(publications(mediaId, "event_publication")).isZero();
        assertThat(publications(mediaId, "event_publication_archive")).isZero();
    }

    @Test
    void whenTheQueueIsDownThePublicationStaysIncompleteAndAResubmissionDeliversExactlyOneMessage() {
        UUID mediaId = UUID.randomUUID();
        UUID owner = new TestApi(rest).register("relay").id();
        doThrow(new IllegalStateException("simulated: SQS unreachable"))
                .doCallRealMethod()
                .when(queue)
                .send(contains(mediaId.toString()), any());

        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        tx.executeWithoutResult(
                status -> events.publishEvent(new MediaUploaded(mediaId, owner, Instant.now(), traceparent)));

        // First delivery fails: the outbox row exists and is not complete; nothing reached the queue.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> verify(queue, times(1)).send(contains(mediaId.toString()), any()));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(dsl.fetchValue(
                        "select count(*) from shared.event_publication where serialized_event like ?"
                                + " and completion_date is null",
                        "%" + mediaId + "%"))
                .isEqualTo(1L));

        // The sweep (every minute in production) retries it: now the send succeeds.
        incomplete.resubmitIncompletePublicationsOlderThan(Duration.ZERO);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> verify(queue, times(2)).send(contains(mediaId.toString()), any()));
        // Completed publications are moved to the archive table (completion-mode: archive).
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(publications(mediaId, "event_publication")).isZero();
            assertThat(publications(mediaId, "event_publication_archive")).isEqualTo(1L);
        });
        // Exactly one message for this asset is on the queue, with the documented body.
        List<JsonNode> mine = drainMessagesFor(mediaId);
        assertThat(mine).hasSize(1);
        // The trace context stored with the event came out the other end as a message attribute.
        assertThat(lastTraceparent).isEqualTo(traceparent);
        assertThat(mine.getFirst().path("attempt").asInt()).isEqualTo(1);
        assertThat(Instant.parse(mine.getFirst().path("enqueuedAt").asText(""))).isBeforeOrEqualTo(Instant.now());

        // Another sweep finds nothing to do.
        incomplete.resubmitIncompletePublicationsOlderThan(Duration.ZERO);
        verify(queue, times(2)).send(contains(mediaId.toString()), any());
    }

    private String lastTraceparent;

    private long publications(UUID mediaId, String table) {
        return dsl.fetchOne(
                        "select count(*) from shared." + table + " where serialized_event like ?", "%" + mediaId + "%")
                .get(0, Long.class);
    }

    /** Receives what is on the test queue and returns (and deletes) the messages for one asset. */
    private List<JsonNode> drainMessagesFor(UUID mediaId) {
        List<JsonNode> mine = new ArrayList<>();
        for (int round = 0; round < 20; round++) {
            List<JobQueue.QueueMessage> batch = queue.receive(10, Duration.ofSeconds(1));
            if (batch.isEmpty()) {
                break;
            }
            for (JobQueue.QueueMessage message : batch) {
                JsonNode body;
                try {
                    body = json.readTree(message.body());
                } catch (JsonProcessingException e) {
                    throw new UncheckedIOException(e);
                }
                if (body.path("mediaId").asText("").equals(mediaId.toString())) {
                    mine.add(body);
                    lastTraceparent = message.attributes().get("traceparent");
                    queue.delete(message.receiptHandle());
                } else {
                    queue.changeVisibility(message.receiptHandle(), Duration.ZERO); // not ours: put it back
                }
            }
        }
        return mine;
    }
}
