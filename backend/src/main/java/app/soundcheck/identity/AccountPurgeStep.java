package app.soundcheck.identity;

import java.util.UUID;

/**
 * One module's part of purging a deleted account (seven days after deletion). Identity owns
 * the account and runs the purge, but it may not know the modules that hold the account's data; so it
 * declares this interface, and every module with data that the database will not remove by itself
 * implements it. Steps run in {@link #order()} inside one transaction per account, then identity deletes
 * the user row, which cascades to everything that references it with {@code on delete cascade}.
 */
public interface AccountPurgeStep {

    /** Lower runs first. A step that removes something another step's data refers to must run later. */
    int order();

    /** Removes this module's data for the user. Must be safe to repeat. */
    void purge(UUID userId);
}
