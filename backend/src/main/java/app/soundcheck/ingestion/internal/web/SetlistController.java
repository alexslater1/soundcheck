package app.soundcheck.ingestion.internal.web;

import app.soundcheck.ingestion.internal.pastgigs.Setlist;
import app.soundcheck.ingestion.internal.pastgigs.SetlistService;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /gigs/{id}/setlist}: the songs of a past gig, from setlist.fm, not stored. */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1")
@Tag(name = "past-gigs", description = "Past concerts from setlist.fm, looked up live and not stored")
public class SetlistController {

    private final SetlistService setlists;

    public SetlistController(SetlistService setlists) {
        this.setlists = setlists;
    }

    @GetMapping("/gigs/{id}/setlist")
    @Operation(
            operationId = "getGigSetlist",
            summary = "The setlist of a past gig from setlist.fm (cached for an hour, never stored). 404 "
                    + "SETLIST_NOT_FOUND when setlist.fm has none (or the gig is still to come); 503 "
                    + "SETLISTS_UNAVAILABLE without a setlist.fm key")
    public Setlist setlist(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Parameter(description = "One act on the bill; without it, the headliner's or the first found")
                    @RequestParam(required = false)
                    UUID artistId) {
        return setlists.forGig(UUID.fromString(jwt.getSubject()), id, artistId);
    }
}
