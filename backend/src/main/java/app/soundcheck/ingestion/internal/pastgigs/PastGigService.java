package app.soundcheck.ingestion.internal.pastgigs;

import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.source.SourceException;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * "Was it this gig?": a fan adding a past gig searches setlist.fm by artist. Three guards
 * keep within setlist.fm's free limits and terms: answers are cached briefly (the terms allow short
 * caching only; the same search from the same fan, or two fans, within the hour costs one call), a
 * daily budget under the 1,440-call cap, and a per-user hourly limit.
 */
@Service
public class PastGigService {

    static final int MAX_CACHED = 500;

    private record Key(String artist, @Nullable Integer year, int page) {}

    private record Cached(PastGigPage page, Instant at) {}

    private final SetlistFmClient client;
    private final RateLimiter rateLimiter;
    private final IngestionProperties.Setlistfm config;
    private final Clock clock;
    private final Map<Key, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Cached> eldest) {
            return size() > MAX_CACHED;
        }
    };
    private final SetlistFmBudget budget;

    public PastGigService(
            SetlistFmClient client,
            RateLimiter rateLimiter,
            IngestionProperties properties,
            SetlistFmBudget budget,
            Clock clock) {
        this.client = client;
        this.rateLimiter = rateLimiter;
        this.config = properties.setlistfm();
        this.budget = budget;
        this.clock = clock;
    }

    public PastGigPage search(UUID userId, String artist, @Nullable Integer year, int page) {
        if (!client.configured()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PAST_GIGS_UNAVAILABLE",
                    "Past gig lookup is not set up (no setlist.fm key)");
        }
        String name = artist.strip().replaceAll("\\s+", " ");
        if (name.length() < 2) {
            throw ApiException.badRequest("ARTIST_NAME_TOO_SHORT", "Give at least two characters of the artist's name");
        }
        Key key = new Key(name.toLowerCase(Locale.ROOT), year, page);
        Instant now = Instant.now(clock);
        synchronized (cache) {
            Cached hit = cache.get(key);
            if (hit != null && Duration.between(hit.at(), now).compareTo(config.cacheFor()) < 0) {
                return hit.page();
            }
        }
        rateLimiter.enforce("past-gigs:" + userId, config.perUserPerHour(), Duration.ofHours(1));
        budget.spend(now);
        PastGigPage result;
        try {
            result = client.search(name, year, page);
        } catch (SourceException e) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY, "PAST_GIGS_FAILED", "setlist.fm could not be reached. Try again.");
        }
        synchronized (cache) {
            cache.put(key, new Cached(result, now));
        }
        return result;
    }
}
