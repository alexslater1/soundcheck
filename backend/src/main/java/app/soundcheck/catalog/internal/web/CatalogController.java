package app.soundcheck.catalog.internal.web;

import app.soundcheck.catalog.ArtistSummary;
import app.soundcheck.catalog.GigSummary;
import app.soundcheck.catalog.internal.CatalogService;
import app.soundcheck.catalog.internal.CatalogService.ArtistDetail;
import app.soundcheck.catalog.internal.CatalogService.Attendance;
import app.soundcheck.catalog.internal.CatalogService.GigDetail;
import app.soundcheck.catalog.internal.CatalogService.GigQuery;
import app.soundcheck.catalog.internal.CatalogService.SearchResults;
import app.soundcheck.catalog.internal.CatalogService.VenueDetail;
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
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    // ── gigs ────────────────────────────────────────────────────────────────

    @GetMapping("/gigs")
    @Operation(
            operationId = "listGigs",
            summary = "Upcoming gigs near a point (default: the viewer's home city), ordered by start time")
    public Page<GigSummary> gigs(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DecimalMin("-90") @DecimalMax("90") Double lat,
            @RequestParam(required = false) @DecimalMin("-180") @DecimalMax("180") Double lng,
            @RequestParam(required = false) @Min(1) @Max(CatalogService.MAX_RADIUS_KM) Integer radiusKm,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) @Size(max = 40) String genre,
            @RequestParam(required = false) @Size(max = 80) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return catalog.gigs(userId(jwt), new GigQuery(lat, lng, radiusKm, from, to, genre, q, cursor, limit));
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
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return catalog.myGigs(userId(jwt), status, when, cursor, limit);
    }

    // ── artists ─────────────────────────────────────────────────────────────

    @GetMapping("/artists")
    @Operation(operationId = "listArtists", summary = "Search artists by name (?q=) or list popular ones")
    public Page<ArtistSummary> artists(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Size(max = 80) String q,
            @RequestParam(required = false) Boolean popular,
            @RequestParam(required = false) Integer limit) {
        return catalog.artists(userId(jwt), q, limit);
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

    public record AttendanceRequest(
            @NotBlank @Schema(allowableValues = {"INTERESTED", "GOING", "WENT"}) String status) {}

    static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
