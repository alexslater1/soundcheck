package app.soundcheck.feed.internal;

import java.util.Optional;
import java.util.UUID;

/**
 * Where feed sessions are kept. A port, because the storage is a deliberate first choice, not the last
 * one: sessions are short-lived, written once and read a few times, which is cache-shaped. Today they
 * are rows in an UNLOGGED PostgreSQL table ({@link JooqFeedSessionStore}); Redis is the documented path
 * when session writes show up in database latency.
 */
public interface FeedSessionStore {

    void save(FeedSession session);

    /** The session, expired or not; the caller decides what an expired one means. */
    Optional<FeedSession> find(UUID id);

    /** Removes every session of a user (account purge). */
    void deleteOf(UUID userId);
}
