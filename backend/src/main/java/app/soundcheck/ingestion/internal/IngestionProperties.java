package app.soundcheck.ingestion.internal;

import app.soundcheck.shared.Redacted;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code soundcheck.ingestion.*}. Keys come from the environment and are never logged. */
@ConfigurationProperties(prefix = "soundcheck.ingestion")
public record IngestionProperties(
        /** {@code fixtures} (recorded JSON, the default) or {@code live} (real APIs; needs the keys). */
        @DefaultValue("fixtures") String mode,
        /** Directory holding {@code ticketmaster/}, {@code lastfm/} and {@code musicbrainz/} recordings. */
        @DefaultValue("../fixtures") String fixturesDir,
        /**
         * Fixtures mode only: move every recorded date forward by whole days so the first recorded day is
         * today. A recording is a snapshot of one week; without this it would show no upcoming gigs a
         * week later. Local start times are kept (19:30 stays 19:30).
         */
        @DefaultValue("true") boolean fixturesShiftToToday,
        /** How far ahead gigs are fetched. */
        @DefaultValue("90") int horizonDays,
        /** Maximum API calls per run before it stops and is recorded as PARTIAL. */
        @DefaultValue("1500") int callBudget,
        @DefaultValue Ticketmaster ticketmaster,
        @DefaultValue Lastfm lastfm,
        @DefaultValue MusicBrainz musicbrainz,
        @DefaultValue Enrichment enrichment,
        @DefaultValue Setlistfm setlistfm,
        @DefaultValue Venues venues) {

    public boolean live() {
        return "live".equalsIgnoreCase(mode);
    }

    public record Lastfm(
            @DefaultValue("https://ws.audioscrobbler.com") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("4") int requestsPerSecond) {

        @Override
        public String toString() {
            return "Lastfm[baseUrl=" + baseUrl + ", apiKey=" + Redacted.of(apiKey) + ", requestsPerSecond="
                    + requestsPerSecond + "]";
        }
    }

    /**
     * Venues' own websites. Off unless {@code enabled} and the venue's key is in {@code sites}:
     * a venue is read only after it has passed the compliance gate.
     */
    public record Venues(
            @DefaultValue("false") boolean enabled,
            /** The keys (from {@code registry}) to read; empty reads none. */
            @DefaultValue({}) List<String> sites,
            @DefaultValue("classpath:venues/london.json") String registry,
            /** Short and stable: some venue firewalls refuse long user-agent strings. */
            @DefaultValue("SoundcheckBot/0.1 (+https://github.com/alexslater1/soundcheck)") String userAgent,
            /** At least this long between two requests to one host (more if robots.txt asks). */
            @DefaultValue("5s") Duration minSpacing,
            /** How long a host that answered 403 or 429 is left alone. */
            @DefaultValue("24h") Duration backOff) {}

    /**
     * setlist.fm: looked up live when a fan adds a past gig, never ingested. The free key
     * allows 2 requests a second and 1,440 a day; results may only be cached for short periods.
     */
    public record Setlistfm(
            @DefaultValue("https://api.setlist.fm") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("2") int requestsPerSecond,
            @DefaultValue("1400") int dailyBudget,
            @DefaultValue("1h") Duration cacheFor,
            /** Lookups per user per hour. */
            @DefaultValue("30") int perUserPerHour) {

        public boolean configured() {
            return !apiKey.isBlank();
        }

        @Override
        public String toString() {
            return "Setlistfm[baseUrl=" + baseUrl + ", apiKey=" + Redacted.of(apiKey) + ", requestsPerSecond="
                    + requestsPerSecond + ", dailyBudget=" + dailyBudget + ", cacheFor=" + cacheFor
                    + ", perUserPerHour=" + perUserPerHour + "]";
        }
    }

    public record MusicBrainz(
            @DefaultValue("https://musicbrainz.org") String baseUrl,
            /** MusicBrainz requires a descriptive User-Agent with a contact address. */
            @DefaultValue("Soundcheck/0.1 (set MUSICBRAINZ_USER_AGENT)") String userAgent,
            /** MusicBrainz allows one request per second per client. */
            @DefaultValue("1") int requestsPerSecond,
            /** A search hit is accepted as the artist only at or above this score. */
            @DefaultValue("95") int minScore) {}

    public record Enrichment(
            /** Artists enriched longer ago than this are refreshed. */
            @DefaultValue("30d") Duration staleAfter,
            /** Cap per run: at 1 MusicBrainz request per second, 300 artists is about five minutes. */
            @DefaultValue("300") int maxArtistsPerRun,
            /** Last.fm tags used by fewer people than this are ignored. */
            @DefaultValue("10") int minTagCount) {}

    public record Ticketmaster(
            @DefaultValue("https://app.ticketmaster.com") String baseUrl,
            @DefaultValue("") String apiKey,
            /** Ticketmaster allows 5 req/s; staying at 4 leaves headroom. */
            @DefaultValue("4") int requestsPerSecond,
            @DefaultValue("200") int pageSize,
            /**
             * Ticketmaster's UK regional markets (London, South, Midlands and Central, Wales and North West,
             * North and North East, Scotland, Ireland, Northern Ireland), used to split a day that exceeds
             * the paging cap. 201 is "All of United Kingdom" and is deliberately absent. Ids and names were
             * read from a live response on 2025-03-05.
             */
            @DefaultValue({"202", "203", "204", "205", "206", "207", "208", "209"}) List<String> marketIds,
            /** Longest we will sleep for a {@code Retry-After} header before retrying. */
            @DefaultValue("30s") Duration maxRetryAfter) {

        @Override
        public String toString() {
            return "Ticketmaster[baseUrl=" + baseUrl + ", apiKey=" + Redacted.of(apiKey) + ", requestsPerSecond="
                    + requestsPerSecond + ", pageSize=" + pageSize + ", marketIds=" + marketIds
                    + ", maxRetryAfter=" + maxRetryAfter + "]";
        }
    }
}
