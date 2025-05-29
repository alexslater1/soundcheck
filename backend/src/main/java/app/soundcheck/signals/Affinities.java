package app.soundcheck.signals;

import java.util.Map;
import java.util.UUID;

/**
 * What a user's engagement says about their taste: scores in [0, 1] per artist, per author
 * and per genre, as they stand now (a score halves every 14 days without new engagement).
 */
public interface Affinities {

    /** The user's strongest artist affinities, at most {@code limit}. */
    Map<UUID, Double> artists(UUID userId, int limit);

    /** The user's strongest author affinities, at most {@code limit}. */
    Map<UUID, Double> authors(UUID userId, int limit);

    /** The user's implicit genre profile: genre id → score. */
    Map<Short, Double> genres(UUID userId);
}
