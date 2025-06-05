package app.soundcheck.catalog;

import java.util.List;
import java.util.UUID;

/**
 * A gig that might be the same show as an incoming listing: the gig with its listings,
 * its venue (the one asked about, or a sibling: another room of the building), and its bill as ids and
 * names in billing order, so the matcher can look for a shared act.
 */
public record GigCandidate(GigRef gig, UUID venueId, List<UUID> artistIds, List<String> artistNames) {}
