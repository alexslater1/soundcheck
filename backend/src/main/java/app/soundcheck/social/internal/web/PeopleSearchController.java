package app.soundcheck.social.internal.web;

import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.social.internal.PeopleSearchService;
import app.soundcheck.social.internal.PeopleSearchService.PersonResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * People in the Search tab. Under {@code /search} beside catalog's artists and gigs, not
 * under {@code /users}, where {@code /users/{handle}} would have to give up the handle "search".
 */
@ConditionalOnRole(Roles.API)
@RestController
@Validated
@Tag(name = "social", description = "Follows and blocks")
public class PeopleSearchController {

    private final PeopleSearchService people;

    public PeopleSearchController(PeopleSearchService people) {
        this.people = people;
    }

    @GetMapping("/api/v1/search/people")
    @Operation(
            operationId = "searchPeople",
            summary = "People by handle or display name (a leading @ is ignored; at least 3 characters after it): "
                    + "the best match first, then people you follow, then your followers (max 10); blocked "
                    + "people never appear")
    public List<PersonResult> search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @NotBlank @Size(min = 3, max = 80) String q,
            @RequestParam(required = false) @Min(1) @Max(PeopleSearchService.LIMIT) Integer limit) {
        return people.search(UUID.fromString(jwt.getSubject()), q, limit == null ? PeopleSearchService.LIMIT : limit);
    }
}
