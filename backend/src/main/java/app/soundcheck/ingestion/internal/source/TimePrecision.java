package app.soundcheck.ingestion.internal.source;

/** How much a listing's start time can be trusted when matching. */
public enum TimePrecision {
    /** The show's start (ticketing sources). */
    EXACT,
    /** Doors opening (most venue sites); the show starts later. */
    DOORS,
    /** Only the day is known (fan-added gigs without a time, setlist.fm). */
    DATE_ONLY
}
