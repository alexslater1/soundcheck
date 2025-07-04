package app.soundcheck.ingestion.internal;

import app.soundcheck.ingestion.internal.source.SourceIssue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What one ingestion run did ({@code ingestion_run.stats} / {@code issues}). The
 * top-level counters are about gigs; {@code venues}, {@code artists} and {@code gigs} record how each
 * entity-resolution decision was made. Not thread-safe: a run processes gigs one at a time.
 */
public final class RunStats {

    /** The stored issues are a sample, at most this many. */
    public static final int MAX_ISSUES = 200;

    /** The {@code stats} key listing the groups read in full ({@link #groupsReadInFull}). */
    public static final String GROUPS_READ_IN_FULL = "groupsReadInFull";

    /** How entities of one kind were resolved. */
    public static final class Resolution {
        public int created;
        public int matchedByExternalId;
        public int matchedFuzzy;

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("created", created);
            map.put("matchedByExternalId", matchedByExternalId);
            map.put("matchedFuzzy", matchedFuzzy);
            return map;
        }
    }

    public int fetched;
    public int created;
    public int updated;
    public int unchanged;
    public int merged;
    /** Listings that almost matched an existing gig: kept apart and logged in {@code match_review}. */
    public int flaggedForReview;
    /** Gigs that gained details (acts, prices, links) from a listing that is not their primary one. */
    public int filled;
    /** Upcoming gigs marked UNLISTED: their only listings' site dropped them for two full reads. */
    public int unlisted;
    /** Listing groups (a venue site's key) read in full this run: the next run compares with this one. */
    public final Set<String> groupsReadInFull = new TreeSet<>();

    public int skipped;
    public int errors;
    public int apiCalls;
    public int pages;
    public final Resolution venues = new Resolution();
    public final Resolution artists = new Resolution();
    public final Resolution gigs = new Resolution();
    /** What the enrichment pass did (see {@code ArtistEnricher.Result}); empty until it has run. */
    public Map<String, Object> enrichment = Map.of();
    /** How venues without coordinates were located (see {@code VenueGeocoder.Result}); empty until it has run. */
    public Map<String, Object> geocoding = Map.of();

    private final Map<String, Integer> issueCounts = new TreeMap<>();
    private final List<SourceIssue> issueSample = new ArrayList<>();

    /**
     * Adds another tally to this one. A gig is resolved into a scratch tally that is merged only once its
     * transaction has committed, so a rolled-back gig leaves no trace in the run's counters.
     */
    public void merge(RunStats other) {
        created += other.created;
        updated += other.updated;
        unchanged += other.unchanged;
        merged += other.merged;
        flaggedForReview += other.flaggedForReview;
        filled += other.filled;
        for (Resolution[] pair :
                new Resolution[][] {{venues, other.venues}, {artists, other.artists}, {gigs, other.gigs}}) {
            pair[0].created += pair[1].created;
            pair[0].matchedByExternalId += pair[1].matchedByExternalId;
            pair[0].matchedFuzzy += pair[1].matchedFuzzy;
        }
        other.issueSample.forEach(this::issue);
    }

    /** Counts every issue; keeps the first {@link #MAX_ISSUES} as the sample. */
    public void issue(SourceIssue issue) {
        issueCounts.merge(issue.kind(), 1, Integer::sum);
        if (issueSample.size() < MAX_ISSUES) {
            issueSample.add(issue);
        }
    }

    public void issue(String kind, String externalId, String detail) {
        issue(new SourceIssue(kind, externalId, detail));
    }

    public Map<String, Integer> issueCounts() {
        return issueCounts;
    }

    public List<SourceIssue> issueSample() {
        return issueSample;
    }

    /** The JSON object stored in {@code ingestion_run.stats}. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fetched", fetched);
        map.put("created", created);
        map.put("updated", updated);
        map.put("unchanged", unchanged);
        map.put("merged", merged);
        map.put("flaggedForReview", flaggedForReview);
        map.put("filled", filled);
        if (unlisted > 0) {
            map.put("unlisted", unlisted);
        }
        if (!groupsReadInFull.isEmpty()) {
            map.put(GROUPS_READ_IN_FULL, List.copyOf(groupsReadInFull));
        }
        map.put("skipped", skipped);
        map.put("errors", errors);
        map.put("apiCalls", apiCalls);
        map.put("pages", pages);
        map.put("venues", venues.toMap());
        map.put("artists", artists.toMap());
        map.put("gigs", gigs.toMap());
        map.put("enrichment", enrichment);
        map.put("geocoding", geocoding);
        map.put("issueCounts", issueCounts);
        return map;
    }
}
