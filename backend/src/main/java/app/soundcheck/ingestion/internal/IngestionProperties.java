package app.soundcheck.ingestion.internal;

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
        /** How far ahead gigs are fetched. */
        @DefaultValue("90") int horizonDays,
        /** Maximum API calls per run before it stops and is recorded as PARTIAL. */
        @DefaultValue("1500") int callBudget,
        @DefaultValue Ticketmaster ticketmaster) {

    public boolean live() {
        return "live".equalsIgnoreCase(mode);
    }

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
            @DefaultValue("30s") Duration maxRetryAfter) {}
}
