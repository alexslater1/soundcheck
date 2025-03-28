package app.soundcheck.identity.internal.web;

import app.soundcheck.catalog.City;
import app.soundcheck.identity.ProfileFacts;
import app.soundcheck.identity.internal.MeService;
import app.soundcheck.identity.internal.UserRecord;
import app.soundcheck.identity.internal.UserRepository;
import app.soundcheck.shared.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /me} and {@code /users/{handle}}. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "me", description = "Own account, onboarding, profiles")
public class MeController {

    private final MeService me;

    public MeController(MeService me) {
        this.me = me;
    }

    @GetMapping("/me")
    @Operation(operationId = "getMe", summary = "The signed-in user's account")
    public MeResponse getMe(@AuthenticationPrincipal Jwt jwt) {
        return MeResponse.from(me.me(userId(jwt)));
    }

    @PatchMapping("/me")
    @Operation(operationId = "updateMe", summary = "Edit profile fields (only the ones sent)")
    public MeResponse updateMe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateMeRequest body) {
        return MeResponse.from(me.update(
                userId(jwt), body.displayName(), body.bio(), body.handle(), body.homeCityId(), body.avatarMediaId()));
    }

    @PutMapping("/me/genres")
    @Operation(operationId = "setMyGenres", summary = "Replace the explicit genre preferences (1–8)")
    public MeResponse setGenres(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GenresRequest body) {
        List<UserRepository.GenrePreference> preferences = body.genres().stream()
                .map(g -> new UserRepository.GenrePreference((short) (int) g.genreId(), g.weight()))
                .toList();
        return MeResponse.from(me.replaceGenres(userId(jwt), preferences));
    }

    @PostMapping("/me/onboarding/complete")
    @Operation(operationId = "completeOnboarding", summary = "Mark onboarding as done")
    public MeResponse completeOnboarding(@AuthenticationPrincipal Jwt jwt) {
        return MeResponse.from(me.completeOnboarding(userId(jwt)));
    }

    @DeleteMapping("/me")
    @Operation(operationId = "deleteMe", summary = "Delete the account (password confirm); purge after 7 days")
    public ResponseEntity<Void> deleteMe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeleteMeRequest body) {
        me.delete(userId(jwt), body.password());
        return ResponseEntity.accepted().build();
    }

    /**
     * "Public" means visible to every signed-in user, not to anonymous callers: only auth, health,
     * genres and cities are exempt from the bearer token, and the profile carries viewer-relative
     * fields ({@code isFollowing}, {@code isBlocked}).
     */
    @GetMapping("/users/{handle}")
    @Operation(operationId = "getUserByHandle", summary = "Public profile (any signed-in user)")
    public ProfileResponse profile(@AuthenticationPrincipal Jwt jwt, @PathVariable String handle) {
        return me.profile(userId(jwt), handle)
                .map(ProfileResponse::from)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "No user with handle " + handle));
    }

    static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public record UpdateMeRequest(
            @Size(min = 1, max = 50) @Schema(nullable = true) String displayName,
            @Size(max = 300) @Schema(nullable = true) String bio,
            @Size(min = 3, max = 30) @Schema(nullable = true) String handle,
            @Schema(nullable = true) Integer homeCityId,

            /** A READY image the caller uploaded; null leaves the avatar as it is. */
            @Schema(nullable = true) UUID avatarMediaId) {}

    public record GenreChoice(
            @NotNull @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(32767) Integer genreId,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Float weight) {}

    public record GenresRequest(@NotEmpty @Size(max = 8) List<@Valid GenreChoice> genres) {}

    public record DeleteMeRequest(@NotBlank String password) {}

    public record HomeCity(int id, String name, @Schema(nullable = true) String region, double lat, double lng) {

        static HomeCity from(City c) {
            return new HomeCity(c.id(), c.name(), c.region(), c.lat(), c.lng());
        }
    }

    public record GenreWeight(int genreId, float weight) {}

    public record Counts(long followers, long following, long posts) {
        static Counts from(ProfileFacts.Counts counts) {
            return new Counts(counts.followers(), counts.following(), counts.posts());
        }
    }

    public record MeResponse(
            UUID id,
            String handle,
            String displayName,
            @Schema(nullable = true) String avatarUrl,
            String email,
            @Schema(nullable = true) String bio,
            @Schema(nullable = true) HomeCity homeCity,
            List<GenreWeight> genres,
            String role,
            boolean onboarded,
            Counts counts) {

        static MeResponse from(MeService.Me m) {
            UserRecord u = m.user();
            return new MeResponse(
                    u.id(),
                    u.handle(),
                    u.displayName(),
                    m.avatarUrl(),
                    u.email(),
                    u.bio(),
                    m.homeCity() == null ? null : HomeCity.from(m.homeCity()),
                    m.genres().stream()
                            .map(g -> new GenreWeight(g.genreId(), g.weight()))
                            .toList(),
                    u.role(),
                    u.onboardedAt() != null,
                    Counts.from(m.counts()));
        }
    }

    /**
     * Public profile: no email, role or onboarding state. {@code isFollowing} and {@code isBlocked} are
     * the viewer's own relation to this user (a profile whose owner blocked the viewer is a 404).
     */
    public record ProfileResponse(
            UUID id,
            String handle,
            String displayName,
            @Schema(nullable = true) String avatarUrl,
            @Schema(nullable = true) String bio,
            @Schema(nullable = true) String homeCityName,
            Counts counts,
            boolean isFollowing,
            boolean isBlocked) {

        static ProfileResponse from(MeService.Profile profile) {
            MeService.Me m = profile.user();
            UserRecord u = m.user();
            return new ProfileResponse(
                    u.id(),
                    u.handle(),
                    u.displayName(),
                    m.avatarUrl(),
                    u.bio(),
                    m.homeCity() == null ? null : m.homeCity().name(),
                    Counts.from(m.counts()),
                    profile.relation().following(),
                    profile.relation().blocked());
        }
    }
}
