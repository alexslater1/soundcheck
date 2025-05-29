package app.soundcheck.feed.internal.ranking;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

/**
 * Exploration. A ranker that only shows what it already believes is good never learns
 * about anything else, and a new post with no engagement never gets a chance. So from the fourth slot
 * on, each slot is given, with probability {@code explorationRate}, to a random post from the "fresh"
 * pool instead of the next ranked one. Such items are flagged: they are a less biased sample for the
 * lab, and the app explains them as "New for you".
 *
 * <p>The random numbers come from a generator seeded per session, so the same session can be rebuilt
 * exactly (tests, debugging).
 */
public final class ExplorationInjector {

    /** The first slots are never given away: positions 0, 1 and 2 are always the top ranked items. */
    static final int FIRST_SLOT = 3;

    /** An item of the final list and whether it is there by exploration. */
    public record Slot<T>(T item, boolean exploration) {}

    private ExplorationInjector() {}

    /**
     * @param ranked the diversified ranking, best first
     * @param fresh  the exploration pool (may overlap with {@code ranked})
     * @param key    what makes two items the same post
     * @return at most {@code size} items without duplicates
     */
    public static <T, K> List<Slot<T>> inject(
            List<T> ranked, List<T> fresh, Function<T, K> key, double rate, int size, long seed) {
        Random random = new Random(seed);
        List<Slot<T>> result = new ArrayList<>(Math.min(size, ranked.size() + fresh.size()));
        Set<K> used = new HashSet<>();
        List<T> pool = new ArrayList<>(fresh);
        int next = 0;
        while (result.size() < size) {
            // One draw per slot, whether or not the pool still has anything: the sequence of draws
            // depends only on the seed and the slot.
            boolean explore = result.size() >= FIRST_SLOT && random.nextDouble() < rate;
            if (explore) {
                pool.removeIf(item -> used.contains(key.apply(item)));
                if (!pool.isEmpty()) {
                    T picked = pool.remove(random.nextInt(pool.size()));
                    used.add(key.apply(picked));
                    result.add(new Slot<>(picked, true));
                    continue;
                }
            }
            while (next < ranked.size() && used.contains(key.apply(ranked.get(next)))) {
                next++;
            }
            if (next >= ranked.size()) {
                break;
            }
            T item = ranked.get(next++);
            used.add(key.apply(item));
            result.add(new Slot<>(item, false));
        }
        return result;
    }
}
