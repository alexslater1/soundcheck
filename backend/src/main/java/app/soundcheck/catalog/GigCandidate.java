package app.soundcheck.catalog;

import java.util.List;
import java.util.UUID;

/**
 * A gig that might be the same show as an incoming listing: the gig with its listings,
 * and its bill as ids and names in billing order, so the matcher can look for a shared act.
 */
public record GigCandidate(GigRef gig, List<UUID> artistIds, List<String> artistNames) {}
