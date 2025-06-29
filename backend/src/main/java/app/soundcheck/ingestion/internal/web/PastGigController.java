package app.soundcheck.ingestion.internal.web;

import app.soundcheck.ingestion.internal.pastgigs.PastGigPage;
import app.soundcheck.ingestion.internal.pastgigs.PastGigService;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /past-gigs}: an artist's past UK concerts from setlist.fm, for adding a gig. */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "past-gigs", description = "Past concerts from setlist.fm, looked up live and not stored")
public class PastGigController {

    private final PastGigService pastGigs;

    public PastGigController(PastGigService pastGigs) {
        this.pastGigs = pastGigs;
    }

    @GetMapping("/past-gigs")
    @Operation(
            operationId = "searchPastGigs",
            summary = "An artist's past UK concerts on setlist.fm, newest first (20 a page). 503 "
                    + "PAST_GIGS_UNAVAILABLE when no setlist.fm key is configured")
    public PastGigPage search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @NotBlank @Size(max = 120) String artist,
            @RequestParam(required = false) @Min(1950) @Max(2100) Integer year,
            @RequestParam(required = false, defaultValue = "1") @Min(1) @Max(20) int page) {
        return pastGigs.search(UUID.fromString(jwt.getSubject()), artist, year, page);
    }
}
