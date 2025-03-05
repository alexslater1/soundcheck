package app.soundcheck.ingestion.internal.source;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * One fetched page: the request that produced it (no secrets), the raw body (kept replayable in
 * {@code ingestion.raw_payload}), the gigs mapped from it and anything odd noticed on the way.
 */
public record SourcePage(String request, JsonNode body, List<SourceGig> gigs, List<SourceIssue> issues) {}
