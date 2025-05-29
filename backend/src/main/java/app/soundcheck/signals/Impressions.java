package app.soundcheck.signals;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** What a user has already been shown, from the event log. */
public interface Impressions {

    /** The posts shown to the user since the given moment, the most recent {@code limit} at most. */
    Set<UUID> seenBy(UUID userId, Instant since, int limit);
}
