package app.soundcheck.catalog.internal.web;

import app.soundcheck.catalog.ArtistSummary;
import app.soundcheck.catalog.GigSummary;
import app.soundcheck.catalog.internal.CatalogService;
import app.soundcheck.catalog.internal.CatalogService.ArtistDetail;
import app.soundcheck.catalog.internal.CatalogService.Attendance;
import app.soundcheck.catalog.internal.CatalogService.GigDay;
import app.soundcheck.catalog.internal.CatalogService.GigDetail;
import app.soundcheck.catalog.internal.CatalogService.GigQuery;
import app.soundcheck.catalog.internal.CatalogService.SearchResults;
import app.soundcheck.catalog.internal.CatalogService.VenueDetail;
import app.soundcheck.catalog.internal.CatalogService.VenueHit;
import app.soundcheck.catalog.internal.SuggestionService;
import app.soundcheck.catalog.internal.UserSubmissionService;
import app.soundcheck.catalog.internal.UserSubmissionService.AddGig;
import app.soundcheck.catalog.internal.UserSubmissionService.Added;
import app.soundcheck.catalog.internal.UserSubmissionService.FoundOn;
import app.soundcheck.catalog.internal.UserSubmissionService.NewVenue;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Catalog endpoints: gigs, attendance, artists, venues and search. Thin: validate, call the service. */
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "catalog", description = "Gigs, artists, venues, attendance, search")
public class CatalogController {

    static final Set<String> SEARCH_TYPES = Set.of("artists", "gigs");

    /** At most this many artists, and as many venues, in one gig filter. */
    static final int MAX_FILTER_IDS = 20;

    private final CatalogService catalog;
    private final UserSubmissionService submissions;
    private final SuggestionService suggestions;

    public CatalogController(CatalogService catalog, UserSubmissionService submissions, SuggestionService suggestions) {
        this.catalog = catalog;
        this.submissions = submissions;
        this.suggestions = suggestions;
    }

    // ── gigs ────────────────────────────────────────────────────────────────

    @GetMapping("/gigs")
    @Operation(
            operationId = "listGigs",
            summary =
                    "Gigs near a point (default: the viewer's home city) from now (or a window), by start time; order=desc walks the window from its end. artistIds: any of these acts; venueIds: at any of these venues; both: both hold")
    public Page<GigSummary> gigs(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DecimalMin("-90") @DecimalMax("90") Double lat,
            @RequestParam(required = false) @DecimalMin("-180") @DecimalMax("180") Double lng,
            @RequestParam(required = false) @Min(1) @Max(CatalogService.MAX_RADIUS_KM) Integer radiusKm,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) @Size(max = 40) String genre,
            @RequestParam(required = false) @Size(max = 80) String q,
            @RequestParam(required = false) @Size(max = MAX_FILTER_IDS) List<UUID> artistIds,
            @RequestParam(required = false) @Size(max = MAX_FILTER_IDS) List<UUID> venueIds,
            @RequestParam(required = false, defaultValue = "false") boolean nationwide,
            @RequestParam(required = false, defaultValue = "asc") @Schema(allowableValues = {"asc", "desc"})
                    String order,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        if (!order.equals("asc") && !order.equals("desc")) {
            throw ApiException.badRequest("ORDER_INVALID", "order must be asc or desc");
        }
        return catalog.gigs(
                userId(jwt),
                new GigQuery(
                        lat,
                        lng,
                        radiusKm,
                        from,
                        to,
                        genre,
                        q,
                        orEmpty(artistIds),
                        orEmpty(venueIds),
                        nationwide,
                        order.equals("desc"),
                        cursor,
                        limit));
    }

    @GetMapping("/gigs/days")
    @Operation(
            operationId = "listGigDays",
            summary = "For a calendar: how many gigs match the GET /gigs filters on each UK day of a window "
                    + "(from and to required, at most 100 days)")
    public List<GigDay> gigDays(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DecimalMin("-90") @DecimalMax("90") Double lat,
            @RequestParam(required = false) @DecimalMin("-180") @DecimalMax("180") Double lng,
            @RequestParam(required = false) @Min(1) @Max(CatalogService.MAX_RADIUS_KM) Integer radiusKm,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) @Size(max = 40) String genre,
            @RequestParam(required = false) @Size(max = 80) String q,
            @RequestParam(required = false) @Size(max = MAX_FILTER_IDS) List<UUID> artistIds,
            @RequestParam(required = false) @Size(max = MAX_FILTER_IDS) List<UUID> venueIds,
            @RequestParam(required = false, defaultValue = "false") boolean nationwide) {
        return catalog.gigDays(
                userId(jwt),
                new GigQuery(
                        lat,
                        lng,
                        radiusKm,
                        from,
                        to,
                        genre,
                        q,
                        orEmpty(artistIds),
                        orEmpty(venueIds),
                        nationwide,
                        false,
                        null,
                        null));
    }

    @PostMapping("/gigs")
    @Operation(
            operationId = "addGig",
            summary = "Add a gig no source lists: 201 when created, 200 when one at that venue that "
                    + "night already matches. The caller is marked GOING (future) or WENT (past).")
    public ResponseEntity<Added<GigSummary>> addGig(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddGigRequest body) {
        Added<GigSummary> added = submissions.addGig(
                userId(jwt),
                new AddGig(
                        body.artistIds(),
                        body.artistNames(),
                        body.venueId(),
                        body.newVenue() == null
                                ? null
                                : new NewVenue(
                                        body.newVenue().name(),
                                        body.newVenue().cityId(),
                                        body.newVenue().cityName(),
                                        body.newVenue().lat(),
                                        body.newVenue().lng()),
                        body.date(),
                        body.time(),
                        body.title(),
                        body.foundOn() == null
                                ? null
                                : new FoundOn(
                                        body.foundOn().source(),
                                        body.foundOn().externalId(),
                                        body.foundOn().url(),
                                        body.foundOn().artistMbid())));
        return ResponseEntity.status(added.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(added);
    }

    @GetMapping("/gigs/{id}")
    @Operation(operationId = "getGig", summary = "Gig detail: line-up, attendance counts, sources")
    public GigDetail gig(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return catalog.gig(userId(jwt), id);
    }

    @PutMapping("/gigs/{id}/attendance")
    @Operation(operationId = "setGigAttendance", summary = "Mark Interested / Going / Went (idempotent)")
    public Attendance setAttendance(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody AttendanceRequest body) {
        return catalog.setAttendance(userId(jwt), id, body.status());
    }

    @DeleteMapping("/gigs/{id}/attendance")
    @Operation(operationId = "clearGigAttendance", summary = "Remove the viewer's attendance (idempotent)")
    public ResponseEntity<Void> clearAttendance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        catalog.clearAttendance(userId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/gigs")
    @Operation(operationId = "listMyGigs", summary = "Gigs the viewer marked, upcoming (default) or past")
    public Page<GigSummary> myGigs(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String when,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return catalog.myGigs(userId(jwt), status, when, from, to, cursor, limit);
    }

    // ── artists ─────────────────────────────────────────────────────────────

    @GetMapping("/artists")
    @Operation(
            operationId = "listArtists",
            summary = "Search artists by name (?q=), or list popular ones (in genreIds and their children when given)")
    public Page<ArtistSummary> artists(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Size(max = 80) String q,
            @RequestParam(required = false) Boolean popular,
            @RequestParam(required = false) @Size(max = 8) List<Short> genreIds,
            @RequestParam(required = false) Integer limit) {
        return catalog.artists(userId(jwt), q, genreIds, limit);
    }

    @PostMapping("/artists")
    @Operation(
            operationId = "addArtist",
            summary = "The act with this name: 200 when the catalog knows it, 201 when it was added")
    public ResponseEntity<Added<ArtistSummary>> addArtist(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddArtistRequest body) {
        Added<ArtistSummary> added = submissions.addArtist(userId(jwt), body.name());
        return ResponseEntity.status(added.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(added);
    }

    @GetMapping("/artists/{id}")
    @Operation(operationId = "getArtist", summary = "Artist detail with the next five gigs")
    public ArtistDetail artist(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return catalog.artist(userId(jwt), id);
    }

    @GetMapping("/artists/{id}/gigs")
    @Operation(operationId = "listArtistGigs", summary = "An artist's upcoming gigs")
    public Page<GigSummary> artistGigs(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return catalog.artistGigs(userId(jwt), id, cursor, limit);
    }

    // ── venues ──────────────────────────────────────────────────────────────

    @GetMapping("/venues")
    @Operation(operationId = "searchVenues", summary = "Venues by name (at most 10), the ones near home first")
    public List<VenueHit> venues(@AuthenticationPrincipal Jwt jwt, @RequestParam @NotBlank @Size(max = 80) String q) {
        return catalog.searchVenues(userId(jwt), q);
    }

    @GetMapping("/venues/{id}")
    @Operation(operationId = "getVenue", summary = "Venue detail")
    public VenueDetail venue(@PathVariable UUID id) {
        return catalog.venue(id);
    }

    @GetMapping("/venues/{id}/gigs")
    @Operation(operationId = "listVenueGigs", summary = "A venue's upcoming gigs")
    public Page<GigSummary> venueGigs(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return catalog.venueGigs(userId(jwt), id, cursor, limit);
    }

    // ── search ──────────────────────────────────────────────────────────────

    @GetMapping("/search")
    @Operation(operationId = "search", summary = "Top results per type (artists, gigs; max 5 each)")
    public SearchResults search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @NotBlank @Size(max = 80) String q,
            @RequestParam(required = false) String types) {
        Set<String> wanted = types == null || types.isBlank()
                ? SEARCH_TYPES
                : Arrays.stream(types.split(","))
                        .map(t -> t.strip().toLowerCase(Locale.ROOT))
                        .filter(t -> !t.isEmpty())
                        .collect(Collectors.toSet());
        // users and posts are accepted as types but return nothing here.
        if (!Set.of("artists", "gigs", "users", "posts").containsAll(wanted)) {
            throw ApiException.badRequest("SEARCH_TYPE_UNKNOWN", "types may contain artists, gigs, users, posts");
        }
        return catalog.search(userId(jwt), q, wanted);
    }

    @GetMapping("/search/suggest")
    @Operation(
            operationId = "suggest",
            summary = "Artists and venues for the Gigs search box, ranked by name match, then by the viewer "
                    + "(followed, near home, their genres), then upcoming gigs (max 8)")
    public List<SuggestionService.Suggestion> suggest(
            @AuthenticationPrincipal Jwt jwt, @RequestParam @NotBlank @Size(min = 2, max = 80) String q) {
        return suggestions.suggest(userId(jwt), q.strip());
    }

    /** Acts by id and/or by name (at least one in all); exactly one of {@code venueId} and {@code newVenue}. */
    public record AddGigRequest(
            @Size(max = 10) @Schema(nullable = true) List<UUID> artistIds,
            @Size(max = 10) @Schema(nullable = true) List<@NotBlank @Size(max = 120) String> artistNames,
            @Schema(nullable = true) UUID venueId,
            @Valid @Schema(nullable = true) NewVenueRequest newVenue,
            @NotNull LocalDate date,
            @Schema(type = "string", format = "time", example = "19:30", nullable = true) LocalTime time,
            @Size(max = 200) @Schema(nullable = true, description = "Defaults to the headliner's name") String title,
            @Valid @Schema(nullable = true) FoundOnRequest foundOn) {}

    /** A new venue: a reference city ({@code cityId}), or a city name with coordinates (from setlist.fm). */
    public record NewVenueRequest(
            @NotBlank @Size(max = 120) String name,
            @Schema(nullable = true) Integer cityId,
            @Size(max = 120) @Schema(nullable = true) String cityName,
            @Schema(nullable = true) Double lat,
            @Schema(nullable = true) Double lng) {}

    /** Where the fan found the gig: today only {@code SETLISTFM}. */
    public record FoundOnRequest(
            @NotBlank @Schema(allowableValues = {"SETLISTFM"}) String source,
            @NotBlank @Size(max = 64) String externalId,
            @Size(max = 300) @Schema(nullable = true) String url,
            @Schema(nullable = true) UUID artistMbid) {}

    public record AddArtistRequest(@NotBlank @Size(max = 120) String name) {}

    public record AttendanceRequest(
            @NotBlank @Schema(allowableValues = {"INTERESTED", "GOING", "WENT"}) String status) {}

    static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static List<UUID> orEmpty(@Nullable List<UUID> ids) {
        return ids == null ? List.of() : ids;
    }
}
