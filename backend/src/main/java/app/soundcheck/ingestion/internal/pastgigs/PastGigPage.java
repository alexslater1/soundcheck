package app.soundcheck.ingestion.internal.pastgigs;

import java.util.List;

/** One page of setlist.fm results (20 per page, as setlist.fm numbers them). */
public record PastGigPage(List<PastGig> items, int page, int total, int itemsPerPage) {}
