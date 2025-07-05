package app.soundcheck.identity;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Identity's read API for other modules: who a user id is. Deleted accounts are not returned. */
public interface Users {

    /** Summaries of the live users among the given ids, in one query. */
    Map<UUID, UserSummary> summaries(Collection<UUID> userIds);

    Optional<UserSummary> summary(UUID userId);

    /** A live user's id by handle (case-insensitive). */
    Optional<UUID> idByHandle(String handle);

    /** The genres the user chose (onboarding, settings): genre id → weight in (0, 1]. */
    Map<Short, Double> genrePreferences(UUID userId);

    /**
     * The users among the ids whose profile is private: their posts, gig stats and gig
     * history are for the people they follow only. Deleted accounts are not returned.
     */
    Set<UUID> privateAmong(Collection<UUID> userIds);

    /** True when the id belongs to a live (not deleted) account. */
    boolean exists(UUID userId);
}
