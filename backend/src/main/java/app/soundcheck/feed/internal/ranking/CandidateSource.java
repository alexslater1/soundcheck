package app.soundcheck.feed.internal.ranking;

/** Where a candidate post came from. A post can have several sources. */
public enum CandidateSource {
    /** Published by someone the viewer follows. */
    FOLLOWING,
    /** Tagged with an artist the viewer follows. */
    ARTIST,
    /** From a gig the viewer is interested in, going to, or went to. */
    GIG,
    /** Tagged artists share one of the viewer's top genres. */
    TASTE,
    /** From a gig near the viewer's home. */
    LOCAL,
    /** Among the most engaged-with posts of the last two days. */
    TRENDING,
    /** New and hardly shown yet: the exploration pool. */
    FRESH
}
