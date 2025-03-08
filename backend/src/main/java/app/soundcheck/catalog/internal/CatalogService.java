package app.soundcheck.catalog.internal;

import app.soundcheck.catalog.ArtistFollows;
import app.soundcheck.catalog.ArtistSummary;
import app.soundcheck.catalog.GeoPoint;
import app.soundcheck.catalog.GigSummary;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    static final int SEARCH_PER_TYPE = 5;
    static final int ARTIST_UPCOMING_GIGS = 5;

    /** {@code GET /gigs} parameters after validation. */
    public record GigQuery(
            @Nullable Double lat,
            @Nullable Double lng,
            @Nullable Integer radiusKm,
            @Nullable Instant from,
            @Nullable Instant to,
            @Nullable String genre,
            @Nullable String q,
            @Nullable String cursor,
            @Nullable Integer limit) {}

    public record AttendanceCounts(long interested, long going, long went) {}

    public record SourceLink(String source, @Schema(nullable = true) String url) {}

    /** A gig's page: the summary plus the full bill, attendance, post count and provenance. */
    public record GigDetail(
            GigSummary gig,
            List<ArtistSummary> lineup,
            AttendanceCounts attendanceCounts,
            long postsCount,
            List<SourceLink> sources) {}

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
            List<GigSummary> upcomingGigs) {}

    public record VenueDetail(
            UUID id,
            String name,
            @Schema(nullable = true) String address,
            @Schema(nullable = true) String city,
            @Schema(nullable = true) String postcode,
            @Schema(nullable = true) GeoPoint location,
            String timezone) {}

    /** {@code GET /search}: top results per requested type. */
    public record SearchResults(List<ArtistSummary> artists, List<GigSummary> gigs) {}

    private final CatalogReadRepository repository;
    private final ViewerHome viewerHome;
    private final ArtistFollows follows;
    private final Clock clock;

    public CatalogService(CatalogReadRepository repository, ViewerHome viewerHome, ArtistFollows follows, Clock clock) {
        this.repository = repository;
        this.viewerHome = viewerHome;
        this.follows = follows;
        this.clock = clock;
    }

    // ── gigs ────────────────────────────────────────────────────────────────

    /**
     * Upcoming gigs near a point, in start order. The point is the request's {@code lat}/{@code lng}, or
     * the viewer's home city when they are omitted; with neither, the list is UK-wide and has no distances.
     */
    public Page<GigSummary> gigs(UUID viewer, GigQuery query) {
        if ((query.lat() == null) != (query.lng() == null)) {
            throw ApiException.badRequest("LOCATION_INCOMPLETE", "Send both lat and lng, or neither");
        }
        GeoPoint origin = query.lat() != null
                ? new GeoPoint(query.lat(), query.lng())
                : viewerHome.homeOf(viewer).orElse(null);
        int radius = query.radiusKm() == null ? DEFAULT_RADIUS_KM : query.radiusKm();
        Set<Short> genreIds = null;
        if (query.genre() != null && !query.genre().isBlank()) {
            genreIds = repository.genreWithChildren(query.genre().strip());
            if (genreIds.isEmpty()) {
                throw ApiException.badRequest("GENRE_UNKNOWN", "Unknown genre " + query.genre());
            }
        }
        Instant from = query.from() == null ? Instant.now(clock) : query.from();
        return page(
                viewer,
                new GigFilter(
                        origin,
                        origin == null ? null : radius,
                        from,
                        query.to(),
                        genreIds,
                        query.q(),
                        null,
                        null,
                        null,
                        null,
                        false,
                        Cursor.decode(query.cursor()),
                        Cursor.limit(query.limit())),
                origin);
    }

    public GigDetail gig(UUID viewer, UUID gigId) {
        GeoPoint origin = viewerHome.homeOf(viewer).orElse(null);
        GigRow row = repository
                .gig(gigId, origin)
                .orElseThrow(() -> ApiException.notFound("GIG_NOT_FOUND", "No gig " + gigId));
        GigSummary summary = summaries(viewer, List.of(row)).getFirst();
        List<ArtistSummary> lineup = new ArrayList<>();
        lineup.add(summary.headliner());
        lineup.addAll(summary.support());
        List<SourceLink> sources = repository.sources(gigId).stream()
                .map(s -> new SourceLink(s.source(), s.url()))
                .toList();
        // postsCount comes from the content module later (catalog cannot depend on content).
        return new GigDetail(summary, lineup, counts(gigId), 0, sources);
    }

    /** Idempotent: marking the same status again changes nothing. */
    @Transactional
    public Attendance setAttendance(UUID viewer, UUID gigId, String status) {
        if (!ATTENDANCE.contains(status)) {
            throw ApiException.badRequest("ATTENDANCE_INVALID", "status must be INTERESTED, GOING or WENT");
        }
        requireGig(gigId);
        repository.setAttendance(viewer, gigId, status, Instant.now(clock));
        return new Attendance(status, counts(gigId));
    }

    /** Idempotent: clearing when nothing is set is still a success. */
    @Transactional
    public void clearAttendance(UUID viewer, UUID gigId) {
        requireGig(gigId);
        repository.clearAttendance(viewer, gigId);
    }

    /** The viewer's gigs: upcoming soonest-first, or past most-recent-first. */
    public Page<GigSummary> myGigs(
            UUID viewer,
            @Nullable String status,
            @Nullable String when,
            @Nullable String cursor,
            @Nullable Integer limit) {
        if (status != null && !ATTENDANCE.contains(status)) {
            throw ApiException.badRequest("ATTENDANCE_INVALID", "status must be INTERESTED, GOING or WENT");
        }
        boolean past = "past".equalsIgnoreCase(when);
        if (when != null && !past && !"upcoming".equalsIgnoreCase(when)) {
            throw ApiException.badRequest("WHEN_INVALID", "when must be upcoming or past");
        }
        Instant now = Instant.now(clock);
        GeoPoint origin = viewerHome.homeOf(viewer).orElse(null);
        return page(
                viewer,
                new GigFilter(
                        origin,
                        null,
                        past ? null : now,
                        past ? now : null,
                        null,
                        null,
                        null,
                        null,
                        viewer,
                        status,
                        past,
                        Cursor.decode(cursor),
                        Cursor.limit(limit)),
                origin);
    }

    // ── artists ─────────────────────────────────────────────────────────────

    /** {@code ?q=} searches by name; {@code ?popular=true} (or no query) lists popular artists. */
    public Page<ArtistSummary> artists(UUID viewer, @Nullable String q, @Nullable Integer limit) {
        int size = Cursor.limit(limit);
        List<UUID> ids = q != null && !q.isBlank()
                ? repository.searchArtists(q, size)
                : repository.popularArtists(Instant.now(clock), size);
        return Page.of(artistSummaries(viewer, ids));
    }

    public ArtistDetail artist(UUID viewer, UUID artistId) {
        ArtistRow row = repository.artists(List.of(artistId)).get(artistId);
        if (row == null) {
            throw ApiException.notFound("ARTIST_NOT_FOUND", "No artist " + artistId);
        }
        ArtistSummary summary = artistSummaries(viewer, List.of(artistId)).getFirst();
        List<GigSummary> upcoming =
                artistGigs(viewer, artistId, null, ARTIST_UPCOMING_GIGS).items();
        return new ArtistDetail(summary, row.mbid(), follows.followerCount(artistId), upcoming);
    }

    public Page<GigSummary> artistGigs(UUID viewer, UUID artistId, @Nullable String cursor, @Nullable Integer limit) {
        if (!repository.artistExists(artistId)) {
            throw ApiException.notFound("ARTIST_NOT_FOUND", "No artist " + artistId);
        }
        GeoPoint origin = viewerHome.homeOf(viewer).orElse(null);
        return page(
                viewer,
                new GigFilter(
                        origin,
                        null,
                        Instant.now(clock),
                        null,
                        null,
                        null,
                        artistId,
                        null,
                        null,
                        null,
                        false,
                        Cursor.decode(cursor),
                        Cursor.limit(limit)),
                origin);
    }

    // ── venues ──────────────────────────────────────────────────────────────

    public VenueDetail venue(UUID venueId) {
        VenueRow v = repository
                .venue(venueId)
                .orElseThrow(() -> ApiException.notFound("VENUE_NOT_FOUND", "No venue " + venueId));
        return new VenueDetail(
                v.id(),
                v.name(),
                v.address(),
                v.city(),
                v.postcode(),
                v.lat() == null || v.lng() == null ? null : new GeoPoint(v.lat(), v.lng()),
                v.timezone());
    }

    public Page<GigSummary> venueGigs(UUID viewer, UUID venueId, @Nullable String cursor, @Nullable Integer limit) {
        venue(venueId);
        GeoPoint origin = viewerHome.homeOf(viewer).orElse(null);
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
                        venueId,
                        null,
                        null,
                        false,
                        Cursor.decode(cursor),
                        Cursor.limit(limit)),
                origin);
    }

    // ── search ──────────────────────────────────────────────────────────────

    /** Top five per type; gigs are upcoming ones, UK-wide, soonest first. */
    public SearchResults search(UUID viewer, String q, Set<String> types) {
        List<ArtistSummary> artists = types.contains("artists")
                ? artistSummaries(viewer, repository.searchArtists(q, SEARCH_PER_TYPE))
                : List.of();
        List<GigSummary> gigs = List.of();
        if (types.contains("gigs")) {
            GeoPoint origin = viewerHome.homeOf(viewer).orElse(null);
            gigs = page(
                            viewer,
                            new GigFilter(
                                    origin,
                                    null,
                                    Instant.now(clock),
                                    null,
                                    null,
                                    q,
                                    null,
                                    null,
                                    null,
                                    null,
                                    false,
                                    null,
                                    SEARCH_PER_TYPE),
                            origin)
                    .items();
        }
        return new SearchResults(artists, gigs);
    }

    // ── assembly ────────────────────────────────────────────────────────────

    /** Runs the filter for {@code limit + 1} rows; the extra row only signals that a next page exists. */
    private Page<GigSummary> page(UUID viewer, GigFilter filter, @Nullable GeoPoint origin) {
        List<GigRow> rows = repository.gigs(filter);
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
        Map<UUID, String> attendance = repository.attendance(viewer, gigIds);
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
                                    : new GeoPoint(row.venueLat(), row.venueLng())),
                    bill.getFirst(),
                    bill.subList(1, bill.size()),
                    row.imageUrl(),
                    row.ticketUrl(),
                    row.priceMin(),
                    row.priceMax(),
                    row.currency(),
                    attendance.get(row.id()),
                    row.distanceKm()));
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
