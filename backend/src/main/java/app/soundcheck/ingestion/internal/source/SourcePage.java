package app.soundcheck.ingestion.internal.source;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One fetched page: the request that produced it (no secrets), the raw body (kept replayable in
 * {@code ingestion.raw_payload}), the gigs mapped from it and anything odd noticed on the way.
 *
 * <p>{@code readInFull} names a group of listings (the prefix of their external ids, e.g. a venue site's
 * key) when this page is the last of a read of that whole group that met no failure and found events. Only
 * then may a listing of the group that is missing be taken as gone; null on every other page.
 */
public record SourcePage(
        String request, JsonNode body, List<SourceGig> gigs, List<SourceIssue> issues, @Nullable String readInFull) {

    public SourcePage(String request, JsonNode body, List<SourceGig> gigs, List<SourceIssue> issues) {
        this(request, body, gigs, issues, null);
    }

    /** This page, marked as completing a full read of {@code group}. */
    public SourcePage completing(String group) {
        return new SourcePage(request, body, gigs, issues, group);
    }
}
