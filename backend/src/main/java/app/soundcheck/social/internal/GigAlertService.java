package app.soundcheck.social.internal;

import app.soundcheck.catalog.NewGigs;
import app.soundcheck.catalog.NewGigs.ArtistRef;
import app.soundcheck.catalog.NewGigs.NewGig;
import app.soundcheck.catalog.NewGigs.Position;
import app.soundcheck.catalog.NewGigs.SearchHit;
import app.soundcheck.shared.Ids;
import app.soundcheck.social.NewGigsFound;
import app.soundcheck.social.NewGigsFound.AlertedGig;
import app.soundcheck.social.NewGigsFound.Reason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * New-gig alerts, sent only when there are new gigs. A pass
 * looks at the gigs created recently, finds who follows one of their acts or their venue, or saved a
 * search they match, and publishes one {@link NewGigsFound} per such user with every gig that user has
 * not been told about yet. Notifications turns each into one inbox entry.
 *
 * <p>Which gigs: created in the last {@link #LOOKBACK} and not before alerts started, upcoming, not
 * cancelled, postponed or unlisted. The window, rather than a cursor that only moves forward, means a gig
 * whose ingestion committed after a later gig was seen is still found; {@code social.gig_alert_sent}
 * makes sure each (user, gig) is announced once. The first pass only records the start, so switching
 * alerts on never announces the whole catalog.
 */
@Service
public class GigAlertService {

    static final Duration LOOKBACK = Duration.ofDays(2);
    static final int PAGE = 1000;

    private static final Logger log = LoggerFactory.getLogger(GigAlertService.class);

    /** What one pass did, for the log and the tests. */
    public record PassResult(boolean started, int gigsSeen, int usersAlerted, int gigsAlerted) {}

    private final NewGigs newGigs;
    private final GigAlertRepository repository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    GigAlertService(NewGigs newGigs, GigAlertRepository repository, ApplicationEventPublisher events, Clock clock) {
        this.newGigs = newGigs;
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public PassResult run() {
        Instant now = Instant.now(clock);
        Instant startedAt = repository.startedAt().orElse(null);
        if (startedAt == null) {
            repository.start(now);
            log.info("New-gig alerts start now: gigs created before {} never alert", now);
            return new PassResult(true, 0, 0, 0);
        }
        Instant since = later(startedAt, now.minus(LOOKBACK));
        List<NewGig> gigs = alertable(since, now);
        Map<UUID, Map<UUID, AlertedGig>> perUser = matches(gigs);
        int users = 0;
        int announced = 0;
        for (Map.Entry<UUID, Map<UUID, AlertedGig>> entry : perUser.entrySet()) {
            UUID userId = entry.getKey();
            Map<UUID, AlertedGig> candidates = entry.getValue();
            Set<UUID> fresh = repository.markSent(
                    userId,
                    candidates.values().stream()
                            .map(g -> new GigAlertRepository.Sent(g.gigId(), g.startsAt()))
                            .toList(),
                    now);
            if (fresh.isEmpty()) {
                continue;
            }
            List<AlertedGig> list = candidates.values().stream()
                    .filter(g -> fresh.contains(g.gigId()))
                    .sorted(Comparator.comparing(AlertedGig::startsAt).thenComparing(AlertedGig::title))
                    .toList();
            events.publishEvent(new NewGigsFound(Ids.newId(), userId, list, now));
            users++;
            announced += list.size();
        }
        int pruned = repository.pruneSent(now.minus(Duration.ofDays(1)));
        if (!gigs.isEmpty() || users > 0) {
            log.info(
                    "New-gig alerts: {} new gigs seen, {} announced to {} users ({} old marks pruned)",
                    gigs.size(),
                    announced,
                    users,
                    pruned);
        }
        return new PassResult(false, gigs.size(), users, announced);
    }

    /** Every gig created since {@code since} that is worth an alert, paging through the creation order. */
    private List<NewGig> alertable(Instant since, Instant now) {
        List<NewGig> out = new ArrayList<>();
        Position after = null;
        while (true) {
            List<NewGig> page = newGigs.createdSince(since, after, PAGE);
            page.stream().filter(g -> g.alertable(now)).forEach(out::add);
            if (page.size() < PAGE) {
                return out;
            }
            NewGig last = page.getLast();
            after = new Position(last.createdAt(), last.id());
        }
    }

    /**
     * User → (gig → why), one reason per gig: an act they follow (the first on the bill), else the venue,
     * else a saved search. A fan is not told about a gig they added themselves.
     */
    private Map<UUID, Map<UUID, AlertedGig>> matches(List<NewGig> gigs) {
        Map<UUID, Map<UUID, AlertedGig>> perUser = new LinkedHashMap<>();
        if (gigs.isEmpty()) {
            return perUser;
        }
        Map<UUID, NewGig> byId = new LinkedHashMap<>();
        gigs.forEach(g -> byId.put(g.id(), g));
        Map<UUID, List<UUID>> artistFans = repository.artistFollowers(gigs.stream()
                .flatMap(g -> g.artists().stream().map(ArtistRef::id))
                .distinct()
                .toList());
        Map<UUID, List<UUID>> venueFans = repository.venueFollowers(
                gigs.stream().map(NewGig::venueId).distinct().toList());
        for (NewGig gig : gigs) {
            for (ArtistRef artist : gig.artists()) {
                for (UUID user : artistFans.getOrDefault(artist.id(), List.of())) {
                    add(perUser, user, gig, Reason.ARTIST, artist.name());
                }
            }
            for (UUID user : venueFans.getOrDefault(gig.venueId(), List.of())) {
                add(perUser, user, gig, Reason.VENUE, gig.venueName());
            }
        }
        for (SearchHit hit : newGigs.savedSearchHits(byId.keySet())) {
            add(perUser, hit.userId(), byId.get(hit.gigId()), Reason.SEARCH, hit.filterName());
        }
        return perUser;
    }

    /** Keeps the first reason found for a (user, gig): the callers go artist, venue, search. */
    private static void add(
            Map<UUID, Map<UUID, AlertedGig>> perUser, UUID user, NewGig gig, Reason reason, String because) {
        if (user.equals(gig.addedBy())) {
            return;
        }
        perUser.computeIfAbsent(user, k -> new LinkedHashMap<>())
                .putIfAbsent(
                        gig.id(),
                        new AlertedGig(
                                gig.id(),
                                gig.title(),
                                gig.startsAt(),
                                gig.venueName(),
                                gig.venueCity(),
                                reason,
                                because));
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
