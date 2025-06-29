package app.soundcheck.catalog.internal.web;

import app.soundcheck.catalog.City;
import app.soundcheck.catalog.Genre;
import app.soundcheck.catalog.ReferenceData;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public reference endpoints: genres and UK city autocomplete. */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "reference", description = "Genres and cities")
public class ReferenceController {

    static final int CITY_LIMIT = 10;

    private final ReferenceData referenceData;

    public ReferenceController(ReferenceData referenceData) {
        this.referenceData = referenceData;
    }

    @GetMapping("/genres")
    @Operation(operationId = "listGenres", summary = "Canonical genre list")
    public List<GenreResponse> genres() {
        Map<Short, List<String>> aliases = referenceData.genreAliases();
        return referenceData.genres().stream()
                .map(g -> GenreResponse.from(g, aliases.getOrDefault(g.id(), List.of())))
                .toList();
    }

    @GetMapping("/cities")
    @Operation(operationId = "searchCities", summary = "UK city autocomplete (max 10, most populous first)")
    public List<CityResponse> cities(@RequestParam(name = "q", required = false) @Size(max = 60) String q) {
        return referenceData.searchCities(q == null ? "" : q, CITY_LIMIT).stream()
                .map(CityResponse::from)
                .toList();
    }

    /** A genre as exposed to clients; {@code aliases} are its other names, for searching (lower case). */
    public record GenreResponse(
            int id, String slug, String name, @Schema(nullable = true) Integer parentId, List<String> aliases) {

        static GenreResponse from(Genre g, List<String> aliases) {
            return new GenreResponse(
                    g.id(), g.slug(), g.name(), g.parentId() == null ? null : (int) g.parentId(), aliases);
        }
    }

    /** A city with its centroid; the app never sends device GPS. */
    public record CityResponse(
            int id,
            String name,
            @Schema(nullable = true) String region,
            double lat,
            double lng,
            @Schema(nullable = true) Integer population) {

        static CityResponse from(City c) {
            return new CityResponse(c.id(), c.name(), c.region(), c.lat(), c.lng(), c.population());
        }
    }
}
