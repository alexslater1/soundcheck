package app.soundcheck.ingestion.internal.source;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * How many API calls a run may make (default 1,500 against Ticketmaster's 5,000/day). Every
 * HTTP attempt counts, retries included. A source stops asking for more pages once it is spent, and
 * the run is then recorded as {@code PARTIAL}.
 */
public final class CallBudget {

    private final int limit;
    private final AtomicInteger used = new AtomicInteger();
    private volatile boolean exhausted;

    public CallBudget(int limit) {
        this.limit = limit;
    }

    /** Counts one HTTP attempt. */
    public void record() {
        used.incrementAndGet();
    }

    /** True while another call fits; flips {@link #exhausted()} the first time the answer is no. */
    public boolean allowsAnotherCall() {
        if (used.get() < limit) {
            return true;
        }
        exhausted = true;
        return false;
    }

    public int used() {
        return used.get();
    }

    /** True when a source wanted to make a call and was refused. */
    public boolean exhausted() {
        return exhausted;
    }
}
