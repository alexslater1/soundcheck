package app.soundcheck.catalog.internal.web;

import app.soundcheck.catalog.internal.SavedFilterService;
import app.soundcheck.catalog.internal.SavedFilterService.NewFilter;
import app.soundcheck.catalog.internal.SavedFilterService.PlaceKind;
import app.soundcheck.catalog.internal.SavedFilterService.SavedGigFilter;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The viewer's saved Gigs-tab searches. */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1/me/gig-filters")
@Validated
@Tag(name = "catalog", description = "Gigs, artists, venues, attendance, search")
public class SavedFilterController {

    private final SavedFilterService filters;

    public SavedFilterController(SavedFilterService filters) {
        this.filters = filters;
    }

    @GetMapping
    @Operation(operationId = "listSavedGigFilters", summary = "The viewer's saved gig searches, oldest first")
    public List<SavedGigFilter> list(@AuthenticationPrincipal Jwt jwt) {
        return filters.list(CatalogController.userId(jwt));
    }

    @PostMapping
    @Operation(
            operationId = "saveGigFilter",
            summary = "Save the current gig search under a name (at most 20; 409 SAVED_FILTERS_FULL)")
    public ResponseEntity<SavedGigFilter> save(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SaveGigFilterRequest body) {
        SavedGigFilter saved = filters.save(
                CatalogController.userId(jwt),
                new NewFilter(
                        body.name(),
                        body.artistIds() == null ? List.of() : body.artistIds(),
                        body.venueIds() == null ? List.of() : body.venueIds(),
                        body.genre(),
                        body.q(),
                        body.place(),
                        body.cityId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteGigFilter", summary = "Delete one of the viewer's saved gig searches")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        filters.delete(CatalogController.userId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    /** {@code place} CITY needs {@code cityId}; HOME and UK ignore it. */
    public record SaveGigFilterRequest(
            @NotBlank @Size(max = 60) String name,
            @Schema(nullable = true) @Nullable @Size(max = CatalogController.MAX_FILTER_IDS) List<UUID> artistIds,
            @Schema(nullable = true) @Nullable @Size(max = CatalogController.MAX_FILTER_IDS) List<UUID> venueIds,
            @Schema(nullable = true) @Nullable @Size(max = 40) String genre,
            @Schema(nullable = true) @Nullable @Size(max = 80) String q,
            @NotNull PlaceKind place,
            @Schema(nullable = true) @Nullable Integer cityId) {}
}
