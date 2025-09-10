package app.soundcheck.catalog.internal;

import app.soundcheck.catalog.ArtistFollows;
import app.soundcheck.catalog.ArtistSummary;
import app.soundcheck.catalog.GeoPoint;
import app.soundcheck.catalog.GigClips;
import app.soundcheck.catalog.GigSummary;
import app.soundcheck.catalog.GigTime;
import app.soundcheck.catalog.VenueFollows;
import app.soundcheck.catalog.VenueSummary;
import app.soundcheck.catalog.ViewerHome;
import app.soundcheck.catalog.internal.CatalogReadRepository.ArtistRow;
import app.soundcheck.catalog.internal.CatalogReadRepository.GigFilter;
import app.soundcheck.catalog.internal.CatalogReadRepository.GigRow;
import app.soundcheck.catalog.internal.CatalogReadRepository.LineupEntry;
import app.soundcheck.catalog.internal.CatalogReadRepository.VenueRow;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Cursor;
import app.soundcheck.shared.web.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The catalog's read side and attendance. Lists are assembled in a fixed number of
 * queries per page — gigs, their line-ups, the artists, their genres, the viewer's attendance and follows
 * — never one query per row.
 */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    public static final int DEFAULT_RADIUS_KM = 25;
    public static final int MAX_RADIUS_KM = 200;
    static final Set<String> ATTENDANCE = Set.of("INTERESTED", "GOING", "WENT");
    static final Set<String> MY_GIGS_WHEN = Set.of("upcoming", "past", "all");
    static final int SEARCH_PER_TYPE = 5;

    /**
     * How far back the Search tab's past gigs go. The gig title has no text index, so the
     * window bounds the scan, as "from now" bounds the upcoming one.
     */
    static final Duration PAST_SEARCH_WINDOW = Duration.ofDays(365);

    static final int ARTIST_UPCOMING_GIGS = 5;

    /** The longest window {@code GET /gigs/days} counts over (a little more than three months). */
    static final long MAX_CALENDAR_DAYS = 100;

    static final int VENUE_SEARCH_LIMIT = 10;

    /**
     * {@code GET /gigs} parameters after validation. {@code nationwide} drops the radius (a search for a
     * gig the user filmed in another city); distances are still measured from the origin.
     * {@code newestFirst} walks the window from its end (the clip picker: last night before last month).
     */
    public record GigQuery(
            @Nullable Double lat,
            @Nullable Double lng,
            @Nullable Integer radiusKm,
            @Nullable Instant from,
            @Nullable Instant to,
            @Nullable String genre,
            @Nullable String q,
            /** Any of these acts; empty: any act. */
            List<UUID> artistIds,
            /** At any of these venues; empty: any venue. */
            List<UUID> venueIds,
            boolean nationwide,
            boolean newestFirst,
            @Nullable String cursor,
            @Nullable Integer limit) {}

    /** {@code GET /gigs/days}: one UK calendar day with at least one matching gig. */
    public record GigDay(LocalDate date, int count) {}

    /** {@code GET /venues}: a venue found by name. */
    public record VenueHit(
            UUID id, String name, @Schema(nullable = true) String city, @Schema(nullable = true) GeoPoint location) {}

    public record AttendanceCounts(long interested, long going, long went) {}

    public record SourceLink(String source, @Schema(nullable = true) String url) {}

    /**
     * A gig's page: the summary plus the full bill, attendance, post count and provenance.
     * {@code postsCount} counts every published clip, private profiles' included; {@code
     * privatePostsCount} is how many of them the viewer cannot see.
     */
    public record GigDetail(
            GigSummary gig,
            List<ArtistSummary> lineup,
            AttendanceCounts attendanceCounts,
            long postsCount,
            long privatePostsCount,
            List<SourceLink> sources,
            @Schema(description = "The viewer added this gig: they may change its date and venue") boolean addedByMe) {}

    /** The acts of the bill the fan saw and the ones they missed, headliner first. */
    public record ActsSeen(List<UUID> seen, List<UUID> missed) {}

    public record Attendance(
            @Schema(
                            nullable = true,
                            allowableValues = {"INTERESTED", "GOING", "WENT"})
                    String myAttendance,
            AttendanceCounts attendanceCounts) {}

    public record ArtistDetail(
            ArtistSummary artist,
            @Schema(nullable = true) UUID mbid,
            long followerCount,
            List<GigSummary> upcomingGigs,
            /** What the viewer hears about this artist; null when they do not follow it. */
            @Schema(
                            nullable = true,
                            allowableValues = {"ALL", "POSTS", "GIGS", "NONE"})
                    String notifyLevel) {}

    public record VenueDetail(
            UUID id,
            String name,
            @Schema(nullable = true) String address,
            @Schema(nullable = true) String city,
            @Schema(nullable = true) String postcode,
            @Schema(nullable = true) GeoPoint location,
            String timezone,
            /** Why this venue's gigs here may be incomplete; null when its listings are read or listed. */
            @Schema(nullable = true) String coverageNote,
            /** Whether the viewer follows this venue (new gigs here then alert them). */
            boolean following,
            long followerCount,
            /** What the viewer hears about this venue; null when they do not follow it. */
            @Schema(
                            nullable = true,
                            allowableValues = {"ALL", "POSTS", "GIGS", "NONE"})
                    String notifyLevel) {}

    /**
     * {@code GET /search}: top results per requested type. People are searched in social and posts are
     * listed per artist or venue in content, so {@code users} and {@code posts} stay empty.
     */
    public record SearchResults(
            List<ArtistSummary> artists,
            List<GigSummary> gigs,
            @Schema(description = "Gigs of the last 12 months, newest first; only with type pastGigs")
                    List<GigSummary> pastGigs) {}

    private final CatalogReadRepository repository;
    private final ViewerHome viewerHome;
    private final ArtistFollows follows;
    private final VenueFollows venueFollows;
    private final GigClips clips;
    private final Clock clock;

    public CatalogService(
            CatalogReadRepository repository,
            ViewerHome viewerHome,
            ArtistFollows follows,
            VenueFollows venueFollows,
            GigClips clips,
            Clock clock) {
        this.repository = repository;
        this.viewerHome = viewerHome;
        this.follows = follows;
        this.venueFollows = venueFollows;
        this.clips = clips;
        this.clock = clock;
    }

    // ── gigs ────────────────────────────────────────────────────────────────

    /**
     * Upcoming gigs near a point, in start order. The point is the request's {@code lat}/{@code lng}, or
     * the viewer's home city when they are omitted; with neither, the list is UK-wide. Distances are from
     * {@code here} (the viewer's rounded position) when sent, else from that point.
     */
    public Page<GigSummary> gigs(UUID viewer, GigQuery query, @Nullable GeoPoint here) {
        GeoPoint origin = originOf(viewer, query);
        return page(viewer, filterFor(query, origin, Instant.now(clock), null), here != null ? here : origin);
    }

    /**
     * {@code GET /gigs/days}: for the calendar, how many gigs match the same filters as {@code GET /gigs}
     * on each UK day of a window of at most {@value #MAX_CALENDAR_DAYS} days. Unlike the list, the window
     * may start in the past (the calendar shows earlier days of the month too).
     */
    public List<GigDay> gigDays(UUID viewer, GigQuery query) {
        if (query.from() == null || query.to() == null) {
            throw ApiException.badRequest("WINDOW_REQUIRED", "from and to are required");
        }
        if (!query.to().isAfter(query.from())
                || Duration.between(query.from(), query.to()).toDays() > MAX_CALENDAR_DAYS) {
            throw ApiException.badRequest(
                    "WINDOW_INVALID", "to must be after from, at most " + MAX_CALENDAR_DAYS + " days later");
        }
        GeoPoint origin = originOf(viewer, query);
        return repository.dayCounts(filterFor(query, origin, query.from(), 0)).stream()
                .map(day -> new GigDay(day.date(), day.count()))
                .toList();
    }

    private GeoPoint originOf(UUID viewer, GigQuery query) {
        if ((query.lat() == null) != (query.lng() == null)) {
            throw ApiException.badRequest("LOCATION_INCOMPLETE", "Send both lat and lng, or neither");
        }
        return query.lat() != null
                ? new GeoPoint(query.lat(), query.lng())
                : viewerHome.homeOf(viewer).orElse(null);
    }

    /** The repository filter for a {@code GET /gigs}-style query; {@code limit} null means the request's. */
    private GigFilter filterFor(
            GigQuery query, @Nullable GeoPoint origin, Instant defaultFrom, @Nullable Integer limit) {
        int radius = query.radiusKm() == null ? DEFAULT_RADIUS_KM : query.radiusKm();
        Set<Short> genreIds = null;
        if (query.genre() != null && !query.genre().isBlank()) {
            genreIds = repository.genreWithChildren(query.genre().strip());
            if (genreIds.isEmpty()) {
                throw ApiException.badRequest("GENRE_UNKNOWN", "Unknown genre " + query.genre());
            }
        }
        Instant from;
        Instant to;
        try {
            from = query.from() == null ? defaultFrom : Cursor.storable(query.from());
            to = query.to() == null ? null : Cursor.storable(query.to());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("DATE_OUT_OF_RANGE", "from and to must be between 1900 and 2200");
        }
        return new GigFilter(
                origin,
                origin == null || query.nationwide() ? null : radius,
                from,
                to,
                genreIds,
                query.q(),
                query.artistIds().isEmpty() ? null : Set.copyOf(query.artistIds()),
                query.venueIds().isEmpty() ? null : Set.copyOf(query.venueIds()),
                null,
                null,
                query.newestFirst(),
                limit == null ? Cursor.decode(query.cursor()) : null,
                limit == null ? Cursor.limit(query.limit()) : limit);
    }

    public GigDetail gig(UUID viewer, UUID gigId, @Nullable GeoPoint here) {
        GeoPoint origin = distancesFrom(viewer, here);
        // A gig merged into another answers as the survivor: old links and notifications still
        // open the right gig, with its posts.
        GigRow row = repository
                .gig(gigId, origin)
                .or(() -> repository.redirectOf(gigId).flatMap(into -> repository.gig(into, origin)))
                .orElseThrow(() -> ApiException.notFound("GIG_NOT_FOUND", "No gig " + gigId));
        UUID id = row.id();
        GigSummary summary = summaries(viewer, List.of(row)).getFirst();
        List<ArtistSummary> lineup = new ArrayList<>();
        lineup.add(summary.headliner());
        lineup.addAll(summary.support());
        List<SourceLink> sources = repository.sources(id).stream()
                .map(s -> new SourceLink(s.source(), s.url()))
                .toList();
        // postsCount is answered by the content module through GigClips (catalog cannot depend on content).
        return new GigDetail(
                summary,
                lineup,
                counts(id),
                clips.countAt(id),
                clips.privateAt(viewer, id),
                sources,
                repository.addedBy(id, viewer));
    }

    /**
     * Idempotent: marking the same status again changes nothing. The time rules ({@link GigTime}):
     * Interested and Going until the gig's UK day ends, Went from its day on, nothing on a cancelled gig.
     */
    @Transactional
    public Attendance setAttendance(UUID viewer, UUID gigId, String status) {
        if (!ATTENDANCE.contains(status)) {
            throw ApiException.badRequest("ATTENDANCE_INVALID", "status must be INTERESTED, GOING or WENT");
        }
        CatalogReadRepository.GigTiming gig =
                repository.timing(gigId).orElseThrow(() -> ApiException.notFound("GIG_NOT_FOUND", "No gig " + gigId));
        Instant now = Instant.now(clock);
        checkAttendance(status, gig, now);
        repository.setAttendance(viewer, gigId, status, now);
        return new Attendance(status, counts(gigId));
    }

    /** The time rules for a fan's own mark; a post's mark is checked by the post's own day rule. */
    static void checkAttendance(String status, CatalogReadRepository.GigTiming gig, Instant now) {
        if ("CANCELLED".equals(gig.status())) {
            throw ApiException.unprocessable("GIG_CANCELLED", "This gig was cancelled");
        }
        if ("WENT".equals(status) && !GigTime.dayHasCome(gig.startsAt(), now)) {
            throw ApiException.unprocessable("GIG_NOT_HAPPENED", "You can say you went once the gig's day has come");
        }
        if (!"WENT".equals(status) && GigTime.isOver(gig.startsAt(), now)) {
            throw ApiException.unprocessable("GIG_IS_OVER", "This gig is over: you can only say you went");
        }
    }

    /**
     * The acts the fan saw at a gig they went to; every other act on the bill is stored
     * as missed. Their mark must read Went (a Going on a gig that is over does: choosing acts confirms
     * it); at least one act, every one on the bill. Idempotent.
     */
    @Transactional
    public ActsSeen setActsSeen(UUID viewer, UUID gigId, List<UUID> seen) {
        repository.timing(gigId).orElseThrow(() -> ApiException.notFound("GIG_NOT_FOUND", "No gig " + gigId));
        CatalogReadRepository.Mark mark =
                repository.attendance(viewer, List.of(gigId)).get(gigId);
        if (mark == null || !"WENT".equals(mark.status())) {
            throw ApiException.unprocessable("NOT_WENT", "Mark the gig Went first");
        }
        List<UUID> bill = repository.lineups(List.of(gigId)).stream()
                .map(LineupEntry::artistId)
                .toList();
        Set<UUID> chosen = new LinkedHashSet<>(seen);
        if (!bill.containsAll(chosen)) {
            throw ApiException.badRequest("ACT_NOT_ON_BILL", "Every act must be on the gig's bill");
        }
        Instant now = Instant.now(clock);
        if (mark.assumed()) {
            repository.setAttendance(viewer, gigId, "WENT", now);
        }
        List<UUID> missed = bill.stream().filter(id -> !chosen.contains(id)).toList();
        repository.setMissed(viewer, gigId, missed, now);
        return new ActsSeen(bill.stream().filter(chosen::contains).toList(), missed);
    }

    /** Idempotent: clearing when nothing is set is still a success. */
    @Transactional
    public void clearAttendance(UUID viewer, UUID gigId) {
        requireGig(gigId);
        repository.clearAttendance(viewer, gigId);
    }

    /**
     * The viewer's gigs: upcoming soonest-first, or past most-recent-first. With {@code from} / {@code to}
     * (the Me tab's calendar) the window replaces {@code when} and the order is by start time.
     */
    public Page<GigSummary> myGigs(
            UUID viewer,
            @Nullable String status,
            @Nullable String when,
            @Nullable Instant windowFrom,
            @Nullable Instant windowTo,
            @Nullable String cursor,
            @Nullable Integer limit,
            @Nullable GeoPoint here) {
        if (status != null && !ATTENDANCE.contains(status)) {
            throw ApiException.badRequest("ATTENDANCE_INVALID", "status must be INTERESTED, GOING or WENT");
        }
        String mode = when == null ? null : when.toLowerCase(Locale.ROOT);
        if (mode != null && !MY_GIGS_WHEN.contains(mode)) {
            throw ApiException.badRequest("WHEN_INVALID", "when must be upcoming, past or all");
        }
        Instant now = Instant.now(clock);
        GeoPoint origin = distancesFrom(viewer, here);
        boolean window = windowFrom != null || windowTo != null;
        // A window alone (the calendar's month) keeps its old meaning: that window, soonest
        // first. Without a window, no `when` means upcoming. Past and all list newest first, inside
        // the window when one is given; past also stops at now and upcoming starts at now.
        Instant from = windowFrom;
        Instant to = windowTo;
        if ("upcoming".equals(mode) || (mode == null && !window)) {
            from = from == null || from.isBefore(now) ? now : from;
        } else if ("past".equals(mode)) {
            to = to == null || to.isAfter(now) ? now : to;
        }
        boolean newestFirst = "past".equals(mode) || "all".equals(mode);
        return page(
                viewer,
                new GigFilter(
                        origin,
                        null,
                        from,
                        to,
                        null,
                        null,
                        null,
                        null,
                        viewer,
                        status,
                        newestFirst,
                        Cursor.decode(cursor),
                        Cursor.limit(limit)),
                origin);
    }

    /** Gig days are UK calendar days. */
    private static final ZoneId UK = ZoneId.of("Europe/London");

    /** How far back Create's gig suggestions look. */
    static final Duration TO_POST_WINDOW = Duration.ofDays(7);

    /**
     * {@code GET /me/gigs/to-post}: the gigs a clip being posted was most likely filmed at, newest
     * first: gigs the viewer marked GOING or WENT in the last seven days, and gigs of acts they follow in the
     * same days within {@value #DEFAULT_RADIUS_KM} km of their home city. Nothing later than today (a clip
     * comes from a gig that has happened).
     */
    public List<GigSummary> gigsToPost(UUID viewer, @Nullable GeoPoint here) {
        Instant now = Instant.now(clock);
        Instant from = now.minus(TO_POST_WINDOW);
        Instant to =
                LocalDate.now(clock.withZone(UK)).plusDays(1).atStartOfDay(UK).toInstant();
        GeoPoint origin = distancesFrom(viewer, here);
        Map<UUID, GigRow> picked = new LinkedHashMap<>();
        // Expected index: gig_attendance's primary key (user_id, gig_id), then gig's primary key.
        for (GigRow row : repository.gigs(
                new GigFilter(origin, null, from, to, null, null, null, null, viewer, null, true, null, 50), origin)) {
            picked.put(row.id(), row);
        }
        Map<UUID, CatalogReadRepository.Mark> mine = repository.attendance(viewer, picked.keySet());
        picked.keySet()
                .removeIf(id -> mine.get(id) == null
                        || (!"GOING".equals(mine.get(id).status())
                                && !"WENT".equals(mine.get(id).status())));
        Optional<GeoPoint> home = viewerHome.homeOf(viewer);
        if (home.isPresent()) {
            // Expected index: gig_location_gix (radius) with gig_starts_at (window): a week near one city.
            List<GigRow> near = repository.gigs(
                    new GigFilter(
                            home.get(),
                            DEFAULT_RADIUS_KM,
                            from,
                            to,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            true,
                            null,
                            300),
                    origin);
            Map<UUID, List<UUID>> bills = new LinkedHashMap<>();
            for (LineupEntry entry :
                    repository.lineups(near.stream().map(GigRow::id).toList())) {
                bills.computeIfAbsent(entry.gigId(), id -> new ArrayList<>()).add(entry.artistId());
            }
            Set<UUID> followed = follows.followedBy(
                    viewer, bills.values().stream().flatMap(List::stream).collect(Collectors.toSet()));
            for (GigRow row : near) {
                if (bills.getOrDefault(row.id(), List.of()).stream().anyMatch(followed::contains)) {
                    picked.putIfAbsent(row.id(), row);
                }
            }
        }
        List<GigRow> newestFirst = picked.values().stream()
                // A cancelled gig did not happen: no clips come from it.
                .filter(row -> !"CANCELLED".equals(row.status()))
                .sorted(Comparator.comparing(GigRow::startsAt).reversed().thenComparing(GigRow::id))
                .limit(30)
                .toList();
        return summaries(viewer, newestFirst);
    }

    /**
     * Someone's WENT gigs, newest first, narrowed to one act on the bill or one venue when given: the
     * gigs behind a line of their stats. The same gigs as the stats count
     * ({@code CatalogReadRepository.happened}). No origin, so no distances.
     */
    public Page<GigSummary> wentGigs(
            UUID viewer,
            UUID attendee,
            @Nullable UUID artistId,
            @Nullable UUID venueId,
            @Nullable String cursor,
            @Nullable Integer limit) {
        return page(viewer, wentFilter(attendee, artistId, venueId, Cursor.decode(cursor), Cursor.limit(limit)), null);
    }

    /**
     * Someone's GOING gigs from today on, soonest first (their profile's Gigs tab); a Going on a gig
     * whose day is over reads as Went and is listed with those. No origin, so no distances.
     * Expected index: the gig_attendance primary key bounds it to their gigs, as for {@link #myGigs}.
     */
    public Page<GigSummary> goingGigs(UUID viewer, UUID attendee, @Nullable String cursor, @Nullable Integer limit) {
        return page(
                viewer,
                new GigFilter(
                        null,
                        null,
                        GigTime.startOfToday(Instant.now(clock)),
                        null,
                        null,
                        null,
                        null,
                        null,
                        attendee,
                        "GOING",
                        false,
                        Cursor.decode(cursor),
                        Cursor.limit(limit)),
                null);
    }

    /** How many gigs {@link #wentGigs} would list in all, across every page. */
    public int wentCount(UUID attendee, @Nullable UUID artistId, @Nullable UUID venueId) {
        return repository.countGigs(wentFilter(attendee, artistId, venueId, null, Cursor.limit(null)));
    }

    private GigFilter wentFilter(
            UUID attendee, @Nullable UUID artistId, @Nullable UUID venueId, @Nullable Cursor cursor, int limit) {
        return new GigFilter(
                null,
                null,
                null,
                null,
                null,
                null,
                artistId == null ? null : Set.of(artistId),
                venueId == null ? null : Set.of(venueId),
                attendee,
                "WENT",
                true,
                cursor,
                limit,
                Instant.now(clock));
    }

    // ── artists ─────────────────────────────────────────────────────────────

    /** {@code ?q=} searches by name; {@code ?popular=true} (or no query) lists popular artists. */
    /**
     * A name search, or else the artists to suggest: popular ones, or popular ones in the given genres
     * (and their children) when there are any — the sign-up step passes the genres just picked.
     */
    public Page<ArtistSummary> artists(
            UUID viewer, @Nullable String q, @Nullable Collection<Short> genreIds, @Nullable Integer limit) {
        int size = Cursor.limit(limit);
        List<UUID> ids;
        if (q != null && !q.isBlank()) {
            ids = repository.searchArtists(q, size);
        } else if (genreIds != null && !genreIds.isEmpty()) {
            ids = repository.popularArtistsInGenres(Instant.now(clock), repository.withChildren(genreIds), size);
        } else {
            ids = repository.popularArtists(Instant.now(clock), size);
        }
        return Page.of(artistSummaries(viewer, ids));
    }

    public ArtistDetail artist(UUID viewer, UUID artistId, @Nullable GeoPoint here) {
        ArtistRow row = repository.artists(List.of(artistId)).get(artistId);
        if (row == null) {
            throw ApiException.notFound("ARTIST_NOT_FOUND", "No artist " + artistId);
        }
        ArtistSummary summary = artistSummaries(viewer, List.of(artistId)).getFirst();
        List<GigSummary> upcoming =
                artistGigs(viewer, artistId, null, ARTIST_UPCOMING_GIGS, here).items();
        return new ArtistDetail(
                summary,
                row.mbid(),
                follows.followerCount(artistId),
                upcoming,
                follows.notifyLevel(viewer, artistId).orElse(null));
    }

    public Page<GigSummary> artistGigs(
            UUID viewer, UUID artistId, @Nullable String cursor, @Nullable Integer limit, @Nullable GeoPoint here) {
        if (!repository.artistExists(artistId)) {
            throw ApiException.notFound("ARTIST_NOT_FOUND", "No artist " + artistId);
        }
        GeoPoint origin = distancesFrom(viewer, here);
        return page(
                viewer,
                new GigFilter(
                        origin,
                        null,
                        Instant.now(clock),
                        null,
                        null,
                        null,
                        Set.of(artistId),
                        null,
                        null,
                        null,
                        false,
                        Cursor.decode(cursor),
                        Cursor.limit(limit)),
                origin);
    }

    // ── venues ──────────────────────────────────────────────────────────────

    public VenueDetail venue(UUID viewer, UUID venueId) {
        VenueRow v = repository
                .venue(venueId)
                .orElseThrow(() -> ApiException.notFound("VENUE_NOT_FOUND", "No venue " + venueId));
        String notify = venueFollows.notifyLevel(viewer, venueId).orElse(null);
        return new VenueDetail(
                v.id(),
                v.name(),
                v.address(),
                v.city(),
                v.postcode(),
                v.lat() == null || v.lng() == null ? null : new GeoPoint(v.lat(), v.lng()),
                v.timezone(),
                v.coverageNote(),
                notify != null,
                venueFollows.followerCount(venueId),
                notify);
    }

    /** Summaries of the venues that exist among the ids (a profile's followed venues). */
    public Map<UUID, VenueSummary> venuesByIds(Collection<UUID> venueIds) {
        Map<UUID, VenueSummary> out = new LinkedHashMap<>();
        for (VenueRow v : repository.venues(venueIds)) {
            out.put(
                    v.id(),
                    new VenueSummary(
                            v.id(),
                            v.name(),
                            v.city(),
                            v.lat() == null || v.lng() == null ? null : new GeoPoint(v.lat(), v.lng()),
                            v.coverageNote()));
        }
        return out;
    }

    /** {@code GET /venues?q=}: venues by name, the ones near the viewer's home first. */
    public List<VenueHit> searchVenues(UUID viewer, String q) {
        GeoPoint origin = viewerHome.homeOf(viewer).orElse(null);
        return repository.searchVenues(q, origin, VENUE_SEARCH_LIMIT).stream()
                .map(v -> new VenueHit(
                        v.id(),
                        v.name(),
                        v.city(),
                        v.lat() == null || v.lng() == null ? null : new GeoPoint(v.lat(), v.lng())))
                .toList();
    }

    public Page<GigSummary> venueGigs(
            UUID viewer, UUID venueId, @Nullable String cursor, @Nullable Integer limit, @Nullable GeoPoint here) {
        if (!repository.venueExists(venueId)) {
            throw ApiException.notFound("VENUE_NOT_FOUND", "No venue " + venueId);
        }
        GeoPoint origin = distancesFrom(viewer, here);
        return page(
                viewer,
                new GigFilter(
                        origin,
                        null,
                        Instant.now(clock),
                        null,
                        null,
                        null,
                        null,
                        Set.of(venueId),
                        null,
                        null,
                        false,
                        Cursor.decode(cursor),
                        Cursor.limit(limit)),
                origin);
    }

    // ── search ──────────────────────────────────────────────────────────────

    /**
     * Top five per type, UK-wide; {@code types} are lower case. Gigs are upcoming ones, soonest first;
     * past gigs ({@code pastgigs}) are those of the last 12 months, newest first.
     */
    public SearchResults search(UUID viewer, String q, Set<String> types, @Nullable GeoPoint here) {
        List<ArtistSummary> artists = types.contains("artists")
                ? artistSummaries(viewer, repository.searchArtists(q, SEARCH_PER_TYPE))
                : List.of();
        Instant now = Instant.now(clock);
        GeoPoint origin = distancesFrom(viewer, here);
        List<GigSummary> gigs = types.contains("gigs") ? searchGigs(viewer, q, now, null, false, origin) : List.of();
        List<GigSummary> past = types.contains("pastgigs")
                ? searchGigs(viewer, q, now.minus(PAST_SEARCH_WINDOW), now, true, origin)
                : List.of();
        return new SearchResults(artists, gigs, past);
    }

    private List<GigSummary> searchGigs(
            UUID viewer, String q, Instant from, @Nullable Instant to, boolean newestFirst, GeoPoint origin) {
        return page(
                        viewer,
                        new GigFilter(
                                origin,
                                null,
                                from,
                                to,
                                null,
                                q,
                                null,
                                null,
                                null,
                                null,
                                newestFirst,
                                null,
                                SEARCH_PER_TYPE),
                        origin)
                .items();
    }

    // ── assembly ────────────────────────────────────────────────────────────

    /** Runs the filter for {@code limit + 1} rows; the extra row only signals that a next page exists. */
    // ── for other modules (CatalogViews) ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<UUID, GigSummary> gigsByIds(UUID viewer, Collection<UUID> gigIds) {
        Map<UUID, GigSummary> byId = new LinkedHashMap<>();
        summaries(viewer, repository.gigsByIds(gigIds)).forEach(gig -> byId.put(gig.id(), gig));
        return byId;
    }

    @Transactional(readOnly = true)
    public Map<UUID, ArtistSummary> artistsByIds(UUID viewer, Collection<UUID> artistIds) {
        Map<UUID, ArtistSummary> byId = new LinkedHashMap<>();
        artistSummaries(viewer, List.copyOf(new LinkedHashSet<>(artistIds)))
                .forEach(artist -> byId.put(artist.id(), artist));
        return byId;
    }

    @Transactional(readOnly = true)
    public List<UUID> lineup(UUID gigId) {
        return repository.lineups(List.of(gigId)).stream()
                .map(LineupEntry::artistId)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<UUID, Map<Short, Double>> genreWeights(Collection<UUID> artistIds) {
        return repository.genreWeights(artistIds);
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> attendanceOf(UUID userId) {
        return repository.attendanceOf(userId, 2_000);
    }

    @Transactional(readOnly = true)
    public boolean venueExists(UUID venueId) {
        return repository.venueExists(venueId);
    }

    @Transactional(readOnly = true)
    public List<UUID> gigIdsAtVenue(UUID venueId, int limit) {
        return repository.gigIdsAtVenue(venueId, limit);
    }

    /**
     * Where distances are measured from when the list has no search area of its own: the viewer's rounded
     * position when the app sent it, else the home city's centre, else nowhere (no distances).
     */
    private @Nullable GeoPoint distancesFrom(UUID viewer, @Nullable GeoPoint here) {
        return here != null ? here : viewerHome.homeOf(viewer).orElse(null);
    }

    /** {@code distancesFrom}: the point each gig's {@code distanceKm} is measured from (null: none). */
    private Page<GigSummary> page(UUID viewer, GigFilter filter, @Nullable GeoPoint distancesFrom) {
        List<GigRow> rows = repository.gigs(filter, distancesFrom);
        boolean more = rows.size() > filter.limit();
        List<GigRow> shown = more ? rows.subList(0, filter.limit()) : rows;
        String next =
                more ? new Cursor(shown.getLast().startsAt(), shown.getLast().id()).encode() : null;
        return new Page<>(summaries(viewer, shown), next);
    }

    private List<GigSummary> summaries(UUID viewer, List<GigRow> rows) {
        List<UUID> gigIds = rows.stream().map(GigRow::id).toList();
        Map<UUID, List<UUID>> lineupByGig = new LinkedHashMap<>();
        Set<UUID> artistIds = new LinkedHashSet<>();
        for (LineupEntry entry : repository.lineups(gigIds)) {
            lineupByGig.computeIfAbsent(entry.gigId(), id -> new ArrayList<>()).add(entry.artistId());
            artistIds.add(entry.artistId());
        }
        Map<UUID, ArtistSummary> artists = new LinkedHashMap<>();
        for (ArtistSummary artist : artistSummaries(viewer, List.copyOf(artistIds))) {
            artists.put(artist.id(), artist);
        }
        Map<UUID, CatalogReadRepository.Mark> attendance = repository.attendance(viewer, gigIds);
        Map<UUID, List<UUID>> missed = repository.missed(viewer, gigIds);
        List<GigSummary> summaries = new ArrayList<>();
        for (GigRow row : rows) {
            List<ArtistSummary> bill = lineupByGig.getOrDefault(row.id(), List.of()).stream()
                    .map(artists::get)
                    .toList();
            if (bill.isEmpty()) {
                continue; // ingestion never stores a gig without a bill; skip rather than fail if one appears
            }
            summaries.add(new GigSummary(
                    row.id(),
                    row.title(),
                    row.startsAt(),
                    row.status(),
                    new VenueSummary(
                            row.venueId(),
                            row.venueName(),
                            row.venueCity(),
                            row.venueLat() == null || row.venueLng() == null
                                    ? null
                                    : new GeoPoint(row.venueLat(), row.venueLng()),
                            row.venueCoverageNote()),
                    bill.getFirst(),
                    bill.subList(1, bill.size()),
                    row.imageUrl(),
                    row.ticketUrl(),
                    row.priceMin(),
                    row.priceMax(),
                    row.currency(),
                    attendance.containsKey(row.id()) ? attendance.get(row.id()).status() : null,
                    row.distanceKm(),
                    row.userAdded(),
                    attendance.containsKey(row.id()) && attendance.get(row.id()).assumed(),
                    missed.getOrDefault(row.id(), List.of())));
        }
        return summaries;
    }

    /** Summaries in the order of {@code ids}. */
    private List<ArtistSummary> artistSummaries(UUID viewer, List<UUID> ids) {
        Map<UUID, ArtistRow> rows = repository.artists(ids);
        Map<UUID, List<String>> genres = repository.genreSlugs(ids);
        Set<UUID> followed = follows.followedBy(viewer, ids);
        return ids.stream()
                .filter(rows::containsKey)
                .map(id -> new ArtistSummary(
                        id,
                        rows.get(id).name(),
                        rows.get(id).imageUrl(),
                        genres.getOrDefault(id, List.of()),
                        followed.contains(id)))
                .toList();
    }

    private AttendanceCounts counts(UUID gigId) {
        Map<String, Long> counts = repository.attendanceCounts(gigId);
        return new AttendanceCounts(
                counts.getOrDefault("INTERESTED", 0L),
                counts.getOrDefault("GOING", 0L),
                counts.getOrDefault("WENT", 0L));
    }

    private void requireGig(UUID gigId) {
        if (!repository.gigExists(gigId)) {
            throw ApiException.notFound("GIG_NOT_FOUND", "No gig " + gigId);
        }
    }
}
