package app.soundcheck.ingestion.internal.pastgigs;

import app.soundcheck.catalog.Catalog;
import app.soundcheck.catalog.GigFacts;
import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.source.SourceException;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The setlist of a past gig, for its page. setlist.fm's
 * terms forbid keeping a copy, so it is looked up when someone opens the page: by its setlist
 * id when the gig was added from setlist.fm, else by act and date — the headliner first, then the
 * others, at most {@link #MAX_ACTS} acts — preferring the setlist from the gig's town. Answers, found or
 * not, are cached for an hour, so a busy gig page costs one call an hour; the calls come out of the same
 * daily budget as the past-gig search, and each user may cause {@link #PER_USER_PER_HOUR} an hour.
 */
@Service
public class SetlistService {

    static final ZoneId UK = ZoneId.of("Europe/London");

    /** Acts tried per gig (one call each): the headliner and the first two supports. */
    static final int MAX_ACTS = 3;

    static final int PER_USER_PER_HOUR = 60;

    static final int MAX_CACHED = 1_000;

    private record Cached(Optional<Setlist> setlist, Instant at) {}

    private final Catalog catalog;
    private final SetlistFmClient client;
    private final SetlistFmBudget budget;
    private final RateLimiter rateLimiter;
    private final IngestionProperties.Setlistfm config;
    private final Clock clock;
    private final Map<UUID, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Cached> eldest) {
            return size() > MAX_CACHED;
        }
    };

    public SetlistService(
            Catalog catalog,
            SetlistFmClient client,
            SetlistFmBudget budget,
            RateLimiter rateLimiter,
            IngestionProperties properties,
            Clock clock) {
        this.catalog = catalog;
        this.client = client;
        this.budget = budget;
        this.rateLimiter = rateLimiter;
        this.config = properties.setlistfm();
        this.clock = clock;
    }

    public Setlist forGig(UUID userId, UUID gigId) {
        if (!client.configured()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SETLISTS_UNAVAILABLE",
                    "Setlists are not set up (no setlist.fm key)");
        }
        GigFacts gig =
                catalog.gigFacts(gigId).orElseThrow(() -> ApiException.notFound("GIG_NOT_FOUND", "No gig " + gigId));
        Instant now = Instant.now(clock);
        if (gig.startsAt().isAfter(now)) {
            throw notFound("This gig has not happened yet");
        }
        synchronized (cache) {
            Cached hit = cache.get(gigId);
            if (hit != null && Duration.between(hit.at(), now).compareTo(config.cacheFor()) < 0) {
                return hit.setlist().orElseThrow(() -> notFound("No setlist for this gig on setlist.fm yet"));
            }
        }
        rateLimiter.enforce("setlists:" + userId, PER_USER_PER_HOUR, Duration.ofHours(1));
        Optional<Setlist> found;
        try {
            found = lookUp(gig, now);
        } catch (SourceException e) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY, "SETLIST_LOOKUP_FAILED", "setlist.fm could not be reached. Try again.");
        }
        synchronized (cache) {
            cache.put(gigId, new Cached(found, now));
        }
        return found.orElseThrow(() -> notFound("No setlist for this gig on setlist.fm yet"));
    }

    private Optional<Setlist> lookUp(GigFacts gig, Instant now) {
        if (gig.setlistFmId() != null) {
            budget.spend(now);
            Optional<Setlist> byId = client.setlist(gig.setlistFmId());
            if (byId.isPresent()) {
                return byId;
            }
        }
        LocalDate day = gig.startsAt().atZone(UK).toLocalDate();
        for (GigFacts.Act act :
                gig.acts().subList(0, Math.min(MAX_ACTS, gig.acts().size()))) {
            budget.spend(now);
            Optional<Setlist> found = pick(client.setlistsOn(act.mbid(), act.name(), day), gig.venueCity());
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** One setlist for the day: the one in the gig's town if there is one, else the only one. */
    static Optional<Setlist> pick(List<Setlist> candidates, @Nullable String town) {
        if (town != null) {
            String wanted = town.strip().toLowerCase(Locale.ROOT);
            Optional<Setlist> local = candidates.stream()
                    .filter(s -> s.city() != null
                            && s.city().strip().toLowerCase(Locale.ROOT).equals(wanted))
                    .findFirst();
            if (local.isPresent()) {
                return local;
            }
        }
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    private static ApiException notFound(String detail) {
        return ApiException.notFound("SETLIST_NOT_FOUND", detail);
    }
}
