package app.soundcheck.ingestion.internal.pastgigs;

import app.soundcheck.catalog.Catalog;
import app.soundcheck.catalog.GigFacts;
import app.soundcheck.catalog.NameNormaliser;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The setlist of a past gig, for its page. setlist.fm's
 * terms forbid keeping a copy, so it is looked up when someone opens the page: by its setlist
 * id when the gig was added from setlist.fm, else by act and date — the headliner first, then the
 * others, at most {@link #MAX_ACTS} acts — preferring the setlist from the gig's town. One act's setlist
 * can be asked for too (the gig page's act bar): support acts have setlists of their own.
 * Answers, found or not, are cached for an hour per gig and act, so a busy gig page costs one call an
 * hour; the calls come out of the same daily budget as the past-gig search, and each user may cause
 * {@link #PER_USER_PER_HOUR} an hour. Lookups are single-flight: when several people open the same
 * uncached page at once, the first asks setlist.fm and the others wait for its answer.
 */
@Service
public class SetlistService {

    static final ZoneId UK = ZoneId.of("Europe/London");

    /** Acts tried per gig (one call each): the headliner and the first two supports. */
    static final int MAX_ACTS = 3;

    static final int PER_USER_PER_HOUR = 60;

    static final int MAX_CACHED = 1_000;

    private record Cached(Optional<Setlist> setlist, Instant at) {}

    /** A cache entry: the gig's setlist (no act) or one act's. */
    private record Key(UUID gigId, @Nullable UUID artistId) {}

    private final Catalog catalog;
    private final SetlistFmClient client;
    private final SetlistFmBudget budget;
    private final RateLimiter rateLimiter;
    private final IngestionProperties.Setlistfm config;
    private final Clock clock;
    private final Map<Key, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Cached> eldest) {
            return size() > MAX_CACHED;
        }
    };

    /** Lookups in progress, by cache key: a second caller for the same key waits for the first. */
    private final Map<Key, CompletableFuture<Optional<Setlist>>> inFlight = new ConcurrentHashMap<>();

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

    /** The gig's setlist: the headliner's, or the first act's that setlist.fm has. */
    public Setlist forGig(UUID userId, UUID gigId) {
        return forGig(userId, gigId, null);
    }

    /**
     * The setlist of one act on the gig's bill, or of the gig as a whole when {@code artistId} is null.
     * An act that is not on the bill has none ({@code SETLIST_NOT_FOUND}, no call made).
     */
    public Setlist forGig(UUID userId, UUID gigId, @Nullable UUID artistId) {
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
        GigFacts.Act act = null;
        boolean tryKnownId = true;
        if (artistId != null) {
            act = gig.acts().stream()
                    .filter(a -> a.id().equals(artistId))
                    .findFirst()
                    .orElseThrow(() -> notFound("That act is not on this gig's bill"));
            Optional<Cached> whole = cached(new Key(gigId, null), now);
            if (whole.isPresent()) {
                Optional<Setlist> found = whole.get().setlist();
                // The whole gig's answer may already be this act's setlist (usually the headliner's).
                if (found.isPresent() && sameArtist(found.get().artist(), act.name())) {
                    return found.get();
                }
                // "None" for the whole gig means each of the first MAX_ACTS acts was searched on this day.
                if (found.isEmpty() && gig.acts().indexOf(act) < MAX_ACTS) {
                    throw notFound("No setlist for this act at this gig on setlist.fm yet");
                }
                // Either way the gig's known setlist id was tried: it is another act's, or it led nowhere.
                tryKnownId = false;
            }
        }
        Key key = new Key(gigId, artistId);
        Optional<Cached> hit = cached(key, now);
        if (hit.isPresent()) {
            return hit.get().setlist().orElseThrow(() -> notFound("No setlist for this gig on setlist.fm yet"));
        }
        rateLimiter.enforce("setlists:" + userId, PER_USER_PER_HOUR, Duration.ofHours(1));
        GigFacts.Act forAct = act;
        boolean knownId = tryKnownId;
        Optional<Setlist> found =
                singleFlight(key, now, () -> forAct == null ? lookUp(gig, now) : lookUp(gig, forAct, knownId, now));
        return found.orElseThrow(() -> notFound("No setlist for this gig on setlist.fm yet"));
    }

    /**
     * Runs {@code lookUp} once per key at a time: the first caller looks up and caches the answer, and any
     * caller arriving meanwhile waits for that answer (or that failure) instead of calling setlist.fm again.
     */
    private Optional<Setlist> singleFlight(Key key, Instant now, LookUp lookUp) {
        CompletableFuture<Optional<Setlist>> mine = new CompletableFuture<>();
        CompletableFuture<Optional<Setlist>> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            try {
                return running.join();
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException cause ? cause : e;
            }
        }
        try {
            // Another lookup for this key may have finished between our cache check and claiming the key.
            Optional<Cached> again = cached(key, now);
            Optional<Setlist> found = again.isPresent() ? again.get().setlist() : lookUp.run();
            synchronized (cache) {
                cache.put(key, new Cached(found, now));
            }
            mine.complete(found);
            return found;
        } catch (SourceException e) {
            ApiException failed = new ApiException(
                    HttpStatus.BAD_GATEWAY, "SETLIST_LOOKUP_FAILED", "setlist.fm could not be reached. Try again.");
            mine.completeExceptionally(failed);
            throw failed;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    @FunctionalInterface
    private interface LookUp {
        Optional<Setlist> run();
    }

    private Optional<Cached> cached(Key key, Instant now) {
        synchronized (cache) {
            Cached hit = cache.get(key);
            return hit != null && Duration.between(hit.at(), now).compareTo(config.cacheFor()) < 0
                    ? Optional.of(hit)
                    : Optional.empty();
        }
    }

    /**
     * One act's setlist on the gig's day: the gig's known setlist.fm id when it is that act's (unless the
     * gig's own lookup already tried it), else a search by the act and the date.
     */
    private Optional<Setlist> lookUp(GigFacts gig, GigFacts.Act act, boolean tryKnownId, Instant now) {
        if (tryKnownId && gig.setlistFmId() != null) {
            budget.spend(now);
            Optional<Setlist> byId = client.setlist(gig.setlistFmId());
            if (byId.isPresent() && sameArtist(byId.get().artist(), act.name())) {
                return byId;
            }
        }
        budget.spend(now);
        LocalDate day = gig.startsAt().atZone(UK).toLocalDate();
        return pick(client.setlistsOn(act.mbid(), act.name(), day), gig.venueCity());
    }

    static boolean sameArtist(String a, String b) {
        return NameNormaliser.normalise(a).equals(NameNormaliser.normalise(b));
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
