package app.soundcheck.catalog;

import java.time.Instant;
import java.util.UUID;

/**
 * One gig a source lists, and whether another source lists the same gig (coverage: how many of a
 * venue's own listings Ticketmaster misses).
 *
 * @param externalId the source's id for its listing
 * @param alsoListed whether the other source has a listing on the same gig
 */
public record ListingOverlap(String externalId, UUID gigId, String title, Instant startsAt, boolean alsoListed) {}
