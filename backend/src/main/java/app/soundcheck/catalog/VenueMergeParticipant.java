package app.soundcheck.catalog;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A module that keeps rows pointing at a venue, and moves them when the operator merges a duplicate venue
 * into the real one. The same shape as {@link GigMergeParticipant}: catalog declares it
 * and calls every implementation inside its own merge transaction; social implements it for venue follows.
 */
public interface VenueMergeParticipant {

    /** How many rows of each kind this module keeps for the venue, by a stable key ("followers" → 12). */
    Map<String, Long> countsAt(UUID venueId);

    /** Why {@code from} can't be merged into {@code into}, one sentence each; empty when nothing stands in the way. */
    List<String> conflicts(UUID from, UUID into);

    /** Re-points this module's rows from {@code from} to {@code into}, inside catalog's merge transaction. */
    void moveTo(UUID from, UUID into);
}
