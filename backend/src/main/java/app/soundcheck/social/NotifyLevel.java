package app.soundcheck.social;

/**
 * How much a follower hears about an artist or a venue they follow. GIGS is the default,
 * what every follow did before levels existed (new-gig alerts); posts are opt-in so nothing new
 * floods anyone's inbox.
 */
public enum NotifyLevel {
    /** New gigs and new posts. */
    ALL,
    /** New posts only. */
    POSTS,
    /** New gigs only (the default). */
    GIGS,
    /** Nothing; still following (the feed and the profile list still use the follow). */
    NONE;

    public boolean gigs() {
        return this == ALL || this == GIGS;
    }

    public boolean posts() {
        return this == ALL || this == POSTS;
    }
}
