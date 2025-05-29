package app.soundcheck.signals;

/**
 * The global engagement prior for ranking feature 8: how engaging a post is on average, and
 * what counts as exceptionally engaging. Recomputed hourly by the scheduler and stored, so every
 * instance reads the same numbers.
 */
public interface EngagementPriors {

    /** The latest prior; {@link Prior#NONE} before the first refresh. */
    Prior current();

    /**
     * @param meanRate {@code m}: (likes + 2·comments + shares + completes) / impressions over all posts
     * @param p99Rate  the 99th percentile of the smoothed per-post rate; feature 8 divides by it
     */
    record Prior(double meanRate, double p99Rate) {
        public static final Prior NONE = new Prior(0, 0);
    }
}
