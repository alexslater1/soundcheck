package app.soundcheck.catalog;

import java.util.UUID;

/**
 * Merging a duplicate gig into the real one, for another module that decides a merge: ingestion's match
 * reviews, where the operator says a new listing's gig is the same show as an existing one. It is
 * the console's own merge: everything moves in one transaction, the duplicate is deleted and
 * its id redirects.
 */
public interface GigMerging {

    /**
     * Moves everything from {@code from} to {@code into}, deletes {@code from} and leaves a redirect. Joins
     * the caller's transaction, so a refusal rolls the caller's own writes back too.
     *
     * @throws app.soundcheck.shared.web.ApiException 409 {@code MERGE_CONFLICT}, {@code MERGE_SAME_GIG} or
     *     {@code GIG_ALREADY_MERGED} (nothing moved), 404 {@code GIG_NOT_FOUND}
     */
    void mergeDuplicate(UUID from, UUID into, UUID operatorId);
}
