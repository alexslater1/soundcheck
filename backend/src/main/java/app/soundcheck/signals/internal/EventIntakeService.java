package app.soundcheck.signals.internal;

import app.soundcheck.content.PostHides;
import app.soundcheck.signals.internal.EventRepository.EventRow;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes a batch of interaction events from a client ({@code POST /events}) and appends it to
 * the event log. The server decides who did it (the token) and when it arrived (the database's clock);
 * the client's timestamp is kept beside it. Nothing here checks that the post exists: the log has no
 * foreign keys, and the rollups skip events about posts that are gone.
 */
@Service
public class EventIntakeService {

    /** An event as the client sent it, already validated. */
    public record IncomingEvent(
            String type,
            UUID postId,
            @Nullable UUID sessionId,
            String surface,
            @Nullable Integer position,
            @Nullable Float value,
            @Nullable Instant clientTs) {}

    static final int MAX_HIDES_PER_BATCH = 10;

    private final EventRepository events;
    private final PostHides postHides;
    private final MeterRegistry meters;
    private final Clock clock;

    EventIntakeService(EventRepository events, PostHides postHides, MeterRegistry meters, Clock clock) {
        this.events = events;
        this.postHides = postHides;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * Stores the batch in one transaction and one INSERT. A HIDE is also "show me less": it is handed to
     * the content module, which owns that list (and ignores a post the user cannot see).
     *
     * @return how many events were stored
     */
    @Transactional
    public int record(UUID userId, @Nullable String platform, List<IncomingEvent> batch) {
        Instant now = Instant.now(clock);
        List<EventRow> rows = batch.stream()
                .map(e -> new EventRow(
                        userId,
                        e.postId(),
                        e.sessionId(),
                        e.surface(),
                        e.position(),
                        e.type(),
                        e.value(),
                        plausible(e.clientTs(), now),
                        platform))
                .toList();
        int stored = events.insertAll(rows);
        batch.stream()
                .filter(e -> e.type().equals("HIDE"))
                .map(IncomingEvent::postId)
                .distinct()
                // Each hide is a few queries; a batch of a hundred of them is not something a person does.
                .limit(MAX_HIDES_PER_BATCH)
                .forEach(postId -> postHides.hideIfVisible(userId, postId));
        Map<String, Long> byType =
                batch.stream().collect(Collectors.groupingBy(IncomingEvent::type, Collectors.counting()));
        byType.forEach((type, count) ->
                meters.counter("soundcheck.events.ingested", "type", type).increment(count));
        return stored;
    }

    /**
     * The device's own timestamp, kept only when it could be true. A clock set to the year 300000 must
     * not fail the whole batch in the database; the event is stored without it.
     */
    private static @Nullable Instant plausible(@Nullable Instant clientTs, Instant now) {
        if (clientTs == null
                || clientTs.isBefore(EARLIEST_CLIENT_TS)
                || clientTs.isAfter(now.plus(Duration.ofDays(1)))) {
            return null;
        }
        return clientTs;
    }

    private static final Instant EARLIEST_CLIENT_TS = Instant.parse("2020-01-01T00:00:00Z");
}
