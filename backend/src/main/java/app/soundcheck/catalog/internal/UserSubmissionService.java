package app.soundcheck.catalog.internal;

import app.soundcheck.catalog.ArtistDraft;
import app.soundcheck.catalog.ArtistSummary;
import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.catalog.City;
import app.soundcheck.catalog.GigDraft;
import app.soundcheck.catalog.GigRef;
import app.soundcheck.catalog.GigSummary;
import app.soundcheck.catalog.GigTime;
import app.soundcheck.catalog.ReferenceData;
import app.soundcheck.catalog.VenueDraft;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.RateLimiter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gigs, venues and artists added by users. Ticketmaster covers few grassroots venues and no
 * past shows, so a fan who filmed a pub gig last Friday had nothing to tag. Each addition first looks for
 * what already exists (same act by normalised name; a similarly named venue near the chosen city; a gig
 * at that venue on the same night with the same headliner or a similar title) and only creates what is
 * missing, so two fans adding the same show end up on one gig. The listing is recorded with source
 * {@code USER}, which lets ingestion attach a ticketing source's listing of the same show later.
 */
@Service
@Transactional
public class UserSubmissionService {

    static final String SOURCE = "USER";
    static final ZoneId UK = ZoneId.of("Europe/London");
    /** When a user gives only a date: most gigs start in the evening. */
    static final LocalTime DEFAULT_START = LocalTime.of(20, 0);

    static final int MAX_NAME = 120;
    static final int MAX_TITLE = 200;
    static final int MAX_ACTS = 10;
    static final int GIGS_PER_DAY = 20;
    static final int ARTISTS_PER_DAY = 60;
    /**
     * A venue with a name at least this similar within this distance of the city is the same venue. A
     * fan's new venue sits at its city's centre, so the distance only rules out other towns and the name
     * has to do the work: only near-identical names join. Measured with pg_trgm on
     * normalised names: "finsbury park" / "finsbury" 0.64 (a park and a pub) and "o2 academy islington" /
     * "o2 academy2 islington" 0.87 (two rooms) stay apart; "the lexington" / "lexington" and "hope &
     * anchor" / "hope and anchor" normalise to the same name (1.0). 0.6 merged the park into the pub.
     */
    static final double VENUE_SIMILARITY = 0.9;

    static final int VENUE_RADIUS_M = 30_000;
    /** Same venue, within this of the start, same headliner or a title at least this similar: same gig. */
    static final Duration SAME_NIGHT = Duration.ofHours(10);

    static final double TITLE_SIMILARITY = 0.5;

    /**
     * A venue that is not in the catalog yet: its name and where it is, either a city from the
     * reference list ({@code cityId}) or a place given by name and coordinates (from setlist.fm).
     */
    public record NewVenue(
            String name,
            @Nullable Integer cityId,
            @Nullable String cityName,
            @Nullable Double lat,
            @Nullable Double lng) {}

    /**
     * Where the fan found the gig, when not typed in by hand: setlist.fm's id and page for it,
     * kept as the gig's listing so the page can link back (setlist.fm's terms), and the act's
     * MusicBrainz id.
     */
    public record FoundOn(String source, String externalId, @Nullable String url, @Nullable UUID artistMbid) {}

    /**
     * {@code POST /gigs}. Acts are existing ids and/or names (a name the catalog does not know adds the
     * act); the venue is an existing id or a new one. {@code time} is UK local time, default 20:00.
     */
    public record AddGig(
            @Nullable List<UUID> artistIds,
            @Nullable List<String> artistNames,
            @Nullable UUID venueId,
            @Nullable NewVenue newVenue,
            LocalDate date,
            @Nullable LocalTime time,
            @Nullable String title,
            @Nullable FoundOn foundOn) {

        public AddGig(
                @Nullable List<UUID> artistIds,
                @Nullable List<String> artistNames,
                @Nullable UUID venueId,
                @Nullable NewVenue newVenue,
                LocalDate date,
                @Nullable LocalTime time,
                @Nullable String title) {
            this(artistIds, artistNames, venueId, newVenue, date, time, title, null);
        }
    }

    /** Listing sources a fan may cite; anything else is refused. */
    static final java.util.Set<String> FOUND_ON = java.util.Set.of("SETLISTFM");

    /** What an add returned: the item, and whether it was created now or already existed. */
    public record Added<T>(T item, @Schema(description = "False when an existing one matched") boolean created) {}

    private final CatalogIngest catalog;
    private final ReferenceData reference;
    private final SubmissionRepository submissions;
    private final CatalogReadRepository reads;
    private final CatalogService views;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    public UserSubmissionService(
            CatalogIngest catalog,
            ReferenceData reference,
            SubmissionRepository submissions,
            CatalogReadRepository reads,
            CatalogService views,
            RateLimiter rateLimiter,
            Clock clock) {
        this.catalog = catalog;
        this.reference = reference;
        this.submissions = submissions;
        this.reads = reads;
        this.views = views;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /** {@code POST /artists}: the act with this name, added when the catalog does not know it. */
    public Added<ArtistSummary> addArtist(UUID userId, String name) {
        Added<UUID> artist = artistNamed(userId, name);
        ArtistSummary summary =
                views.artistsByIds(userId, List.of(artist.item())).get(artist.item());
        return new Added<>(summary, artist.created());
    }

    /** {@code POST /gigs}: the gig, added when no gig at that venue that night matches. */
    public Added<GigSummary> addGig(UUID userId, AddGig request) {
        LocalDate today = LocalDate.now(clock.withZone(UK));
        if (request.date().isBefore(today.minusYears(10)) || request.date().isAfter(today.plusYears(2))) {
            throw ApiException.badRequest(
                    "GIG_DATE_OUT_OF_RANGE", "The date must be within the last ten years or the next two");
        }
        String title = request.title() == null ? null : request.title().strip();
        if (title != null && (title.isEmpty() || title.length() > MAX_TITLE)) {
            throw ApiException.badRequest("GIG_TITLE_INVALID", "A title has 1 to " + MAX_TITLE + " characters");
        }
        FoundOn found = request.foundOn();
        if (found != null) {
            if (!FOUND_ON.contains(found.source())
                    || found.externalId().isBlank()
                    || found.externalId().length() > 64) {
                throw ApiException.badRequest("FOUND_ON_INVALID", "Unknown listing source " + found.source());
            }
            if (found.url() != null && !found.url().startsWith("https://www.setlist.fm/")) {
                throw ApiException.badRequest("FOUND_ON_INVALID", "A setlist.fm link must point at www.setlist.fm");
            }
            // The same setlist added before (by anyone): that gig.
            var known = catalog.gigBySource(found.source(), found.externalId());
            if (known.isPresent()) {
                return attendAndReturn(userId, known.get().id(), false);
            }
        }
        List<UUID> acts = actsOf(userId, request);
        UUID venueId = venueOf(userId, request);
        Instant startsAt = request.date()
                .atTime(request.time() == null ? DEFAULT_START : request.time())
                .atZone(UK)
                .toInstant();
        UUID headliner = acts.getFirst();
        String gigTitle = title != null
                ? title
                : views.artistsByIds(userId, List.of(headliner)).get(headliner).name();

        List<GigRef> sameNight =
                catalog.gigsAtVenueAround(venueId, startsAt, SAME_NIGHT, headliner, gigTitle, TITLE_SIMILARITY);
        boolean created = sameNight.isEmpty();
        UUID gigId;
        if (created) {
            rateLimiter.enforce("user-gigs:" + userId, GIGS_PER_DAY, Duration.ofDays(1));
            gigId = catalog.createGig(new GigDraft(
                    gigTitle,
                    venueId,
                    startsAt,
                    null,
                    "SCHEDULED",
                    null,
                    null,
                    null,
                    null,
                    null,
                    hash(gigTitle, venueId, startsAt, acts),
                    acts));
            catalog.linkGig(SOURCE, gigId.toString(), gigId, null, Instant.now(clock), true, false);
            submissions.markGigAddedBy(gigId, userId);
        } else {
            gigId = sameNight.getFirst().id();
        }
        if (found != null) {
            // Not primary: the fan's listing owns the fields; this one is the link back to setlist.fm.
            catalog.linkGig(found.source(), found.externalId(), gigId, found.url(), Instant.now(clock), false, false);
        }
        return attendAndReturn(userId, gigId, created);
    }

    /**
     * Whoever adds a gig was (or will be) there: it goes on their calendar, as Went once its day has come
     * and Going before. The gig's own date decides, not the one asked for: an existing gig found
     * by its setlist may be on another day. A cancelled gig is not marked.
     */
    private Added<GigSummary> attendAndReturn(UUID userId, UUID gigId, boolean created) {
        Instant now = Instant.now(clock);
        CatalogReadRepository.GigTiming timing = reads.timing(gigId).orElseThrow();
        if (!"CANCELLED".equals(timing.status())
                && !reads.attendance(userId, List.of(gigId)).containsKey(gigId)) {
            String status = GigTime.dayHasCome(timing.startsAt(), now) ? "WENT" : "GOING";
            reads.setAttendance(userId, gigId, status, now);
        }
        GigSummary summary = views.gigsByIds(userId, List.of(gigId)).get(gigId);
        return new Added<>(summary, created);
    }

    private List<UUID> actsOf(UUID userId, AddGig request) {
        Set<UUID> acts = new LinkedHashSet<>();
        List<UUID> ids = request.artistIds() == null ? List.of() : request.artistIds();
        if (!ids.isEmpty()) {
            Set<UUID> known = views.artistsByIds(userId, ids).keySet();
            for (UUID id : ids) {
                if (!known.contains(id)) {
                    throw ApiException.badRequest("ARTIST_UNKNOWN", "Unknown artist " + id);
                }
                acts.add(id);
            }
        }
        List<String> names = request.artistNames() == null ? List.of() : request.artistNames();
        UUID mbid = request.foundOn() == null ? null : request.foundOn().artistMbid();
        for (String name : names) {
            // A MusicBrainz id from setlist.fm identifies the act exactly (only meaningful for one name).
            acts.add(
                    names.size() == 1 && mbid != null
                            ? artistWithMbid(userId, name, mbid)
                            : artistNamed(userId, name).item());
        }
        if (acts.isEmpty()) {
            throw ApiException.badRequest("GIG_NEEDS_AN_ACT", "Name at least one act");
        }
        if (acts.size() > MAX_ACTS) {
            throw ApiException.badRequest("TOO_MANY_ARTISTS", "A gig can list at most " + MAX_ACTS + " acts");
        }
        return new ArrayList<>(acts);
    }

    private UUID venueOf(UUID userId, AddGig request) {
        if ((request.venueId() == null) == (request.newVenue() == null)) {
            throw ApiException.badRequest("VENUE_REQUIRED", "Send either venueId or newVenue");
        }
        if (request.venueId() != null) {
            if (!reads.venueExists(request.venueId())) {
                throw ApiException.badRequest("VENUE_UNKNOWN", "Unknown venue " + request.venueId());
            }
            return request.venueId();
        }
        NewVenue venue = request.newVenue();
        String name = cleanName(venue.name(), "VENUE_NAME_INVALID");
        String cityName;
        double lat;
        double lng;
        if (venue.cityId() != null) {
            City city = reference
                    .city(venue.cityId())
                    .orElseThrow(() -> ApiException.badRequest("CITY_UNKNOWN", "Unknown city " + venue.cityId()));
            cityName = city.name();
            lat = city.lat();
            lng = city.lng();
        } else if (venue.lat() != null && venue.lng() != null && venue.cityName() != null) {
            if (Math.abs(venue.lat()) > 90 || Math.abs(venue.lng()) > 180) {
                throw ApiException.badRequest("VENUE_LOCATION_INVALID", "Coordinates out of range");
            }
            cityName = cleanName(venue.cityName(), "VENUE_CITY_INVALID");
            lat = venue.lat();
            lng = venue.lng();
        } else {
            throw ApiException.badRequest(
                    "VENUE_PLACE_REQUIRED", "A new venue needs a cityId, or a cityName with lat and lng");
        }
        // Another fan (or Ticketmaster) may have it already: a similar name near the city is the same venue.
        return catalog.venueNear(lat, lng, VENUE_RADIUS_M, name, VENUE_SIMILARITY, SOURCE)
                .orElseGet(() -> {
                    // Located at the city's centre: close enough for "near me" lists until a source supplies
                    // the real coordinates (completeVenue never overwrites, so that is a known limit).
                    UUID id = catalog.createVenue(new VenueDraft(name, null, cityName, null, lat, lng, UK.getId()));
                    submissions.markVenueAddedBy(id, userId);
                    return id;
                });
    }

    private UUID artistWithMbid(UUID userId, String rawName, UUID mbid) {
        var byMbid = catalog.artistByMbid(mbid);
        if (byMbid.isPresent()) {
            return byMbid.get();
        }
        String name = cleanName(rawName, "ARTIST_NAME_INVALID");
        var byName = catalog.artistByName(name, mbid);
        if (byName.isPresent()) {
            catalog.completeArtist(byName.get(), mbid, null);
            return byName.get();
        }
        rateLimiter.enforce("user-artists:" + userId, ARTISTS_PER_DAY, Duration.ofDays(1));
        UUID id = catalog.createArtist(new ArtistDraft(name, mbid, null, Map.of()));
        submissions.markArtistAddedBy(id, userId);
        return id;
    }

    private Added<UUID> artistNamed(UUID userId, String rawName) {
        String name = cleanName(rawName, "ARTIST_NAME_INVALID");
        var existing = catalog.artistByName(name, null);
        if (existing.isPresent()) {
            return new Added<>(existing.get(), false);
        }
        rateLimiter.enforce("user-artists:" + userId, ARTISTS_PER_DAY, Duration.ofDays(1));
        UUID id = catalog.createArtist(new ArtistDraft(name, null, null, Map.of()));
        submissions.markArtistAddedBy(id, userId);
        return new Added<>(id, true);
    }

    /** Trimmed, inner whitespace collapsed, 1–120 characters, no control characters. */
    static String cleanName(@Nullable String raw, String code) {
        String name = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > MAX_NAME || name.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest(code, "A name has 1 to " + MAX_NAME + " characters");
        }
        return name;
    }

    private static byte[] hash(String title, UUID venueId, Instant startsAt, List<UUID> acts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((title + "|" + venueId + "|" + startsAt + "|" + acts).getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
