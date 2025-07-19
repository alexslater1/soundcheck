package app.soundcheck.identity;

import java.util.Collection;
import java.util.List;
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
     * The users among the ids whose profile is private: their posts, gig stats and gig history
     * are for their followers only (social's {@code ProfileAccess} applies the rule). Deleted accounts
     * are not returned.
     */
    Set<UUID> privateAmong(Collection<UUID> userIds);

    /** True when the id belongs to a live (not deleted) account. */
    boolean exists(UUID userId);

    /**
     * Live users whose handle or display name matches {@code q} (a leading {@code @} is ignored), best
     * match first, at most {@code limit}; none for fewer than three characters. Private profiles are included: they can be
     * found, only their content is withheld. {@code excludedUserId} (the person searching) is never
     * returned: nobody searches for themselves.
     */
    List<UserMatch> search(String q, int limit, UUID excludedUserId);
}
