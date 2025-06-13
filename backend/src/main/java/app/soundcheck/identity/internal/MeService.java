package app.soundcheck.identity.internal;

import app.soundcheck.catalog.City;
import app.soundcheck.catalog.ReferenceData;
import app.soundcheck.identity.ProfileFacts;
import app.soundcheck.identity.UserDeleted;
import app.soundcheck.identity.UserSummary;
import app.soundcheck.identity.Users;
import app.soundcheck.identity.internal.auth.AuthService;
import app.soundcheck.identity.internal.auth.RefreshTokenRepository;
import app.soundcheck.shared.media.PublicImages;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.RateLimitProperties;
import app.soundcheck.shared.web.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The signed-in user's own account ({@code /me}) and public profiles ({@code /users/{handle}}). */
@Service
@Transactional
public class MeService {

    static final int MIN_GENRES = 1;
    static final int MAX_GENRES = 8;

    /**
     * Everything {@code GET /me} shows; counts are zero until social/content exist.
     * {@code showGigStats}: the user shares their gig stats with other users.
     */
    public record Me(
            UserRecord user,
            @Nullable City homeCity,
            List<UserRepository.GenrePreference> genres,
            @Nullable String avatarUrl,
            ProfileFacts.Counts counts,
            boolean showGigStats) {}

    /**
     * Someone's public profile as a particular viewer sees it. {@code statsVisible}: the viewer may see
     * the owner's gig stats (their own, or someone who shares them).
     */
    public record Profile(Me user, ProfileFacts.Relation relation, boolean statsVisible) {}

    /** As {@code PasswordPolicy.MAX_LENGTH} at sign-up and login. */
    static final int MAX_PASSWORD_LENGTH = 128;

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final ReferenceData referenceData;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Users summaries;
    private final PublicImages images;
    private final ProfileFacts facts;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties.Auth authLimits;

    public MeService(
            Users summaries,
            PublicImages images,
            ProfileFacts facts,
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            ReferenceData referenceData,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher events,
            Clock clock,
            RateLimiter rateLimiter,
            RateLimitProperties rateLimits) {
        this.rateLimiter = rateLimiter;
        this.authLimits = rateLimits.auth();
        this.summaries = summaries;
        this.images = images;
        this.facts = facts;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.referenceData = referenceData;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.clock = clock;
    }

    /** The live (not deleted) user behind a token, or {@code 401 ACCOUNT_DELETED}. */
    @Transactional(readOnly = true)
    public UserRecord current(UUID userId) {
        return users.findById(userId)
                .filter(u -> !u.deleted())
                .orElseThrow(() -> ApiException.unauthorized("ACCOUNT_DELETED", "This account no longer exists"));
    }

    @Transactional(readOnly = true)
    public Me me(UUID userId) {
        UserRecord user = current(userId);
        return new Me(
                user,
                city(user.homeCityId()),
                users.genres(userId),
                avatarUrl(userId),
                facts.counts(userId),
                users.showsGigStats(userId));
    }

    private @Nullable String avatarUrl(UUID userId) {
        return summaries.summary(userId).map(UserSummary::avatarUrl).orElse(null);
    }

    /**
     * A profile by handle. Empty when there is no such live account, and also when its owner has blocked
     * the viewer: a blocked person is not told they are blocked, the profile is simply not there.
     */
    @Transactional(readOnly = true)
    public Optional<Profile> profile(UUID viewerId, String handle) {
        return users.findByHandle(handle.strip().toLowerCase(Locale.ROOT))
                .filter(u -> !u.deleted())
                .map(u -> {
                    boolean shows = users.showsGigStats(u.id());
                    Me user = new Me(
                            u,
                            city(u.homeCityId()),
                            users.genres(u.id()),
                            avatarUrl(u.id()),
                            facts.counts(u.id()),
                            shows);
                    ProfileFacts.Relation relation = facts.relation(viewerId, u.id());
                    return new Profile(user, relation, statsVisible(viewerId, u.id(), shows, relation));
                })
                .filter(p -> !p.relation().blockedBy());
    }

    /**
     * Gig stats are shown to their owner, and to anyone else only when the owner shares them and neither
     * has blocked the other: they tell where someone was on which nights.
     */
    static boolean statsVisible(UUID viewerId, UUID ownerId, boolean shared, ProfileFacts.Relation relation) {
        return viewerId.equals(ownerId) || (shared && !relation.blocked() && !relation.blockedBy());
    }

    public Me update(
            UUID userId,
            @Nullable String displayName,
            @Nullable String bio,
            @Nullable String handle,
            @Nullable Integer homeCityId,
            @Nullable UUID avatarMediaId,
            @Nullable Boolean showGigStats) {
        UserRecord user = current(userId);
        if (avatarMediaId != null && !images.readyImageOwnedBy(avatarMediaId, userId)) {
            // Not an image, not ready, or somebody else's: one answer for all three, so ids cannot be probed.
            throw ApiException.badRequest("AVATAR_INVALID", "avatarMediaId must be an image you uploaded");
        }
        String newHandle = user.handle();
        if (handle != null) {
            newHandle = handle.strip().toLowerCase(Locale.ROOT);
            if (!AuthService.HANDLE.matcher(newHandle).matches()) {
                throw ApiException.badRequest("HANDLE_INVALID", "Handle must be 3–30 characters: a–z, 0–9, _");
            }
            if (AuthService.RESERVED_HANDLES.contains(newHandle) && !user.role().equals("ADMIN")) {
                throw ApiException.conflict("HANDLE_RESERVED", "That handle is reserved");
            }
            if (!newHandle.equals(user.handle()) && users.handleExists(newHandle)) {
                throw ApiException.conflict("HANDLE_TAKEN", "That handle is taken");
            }
        }
        City city = null;
        if (homeCityId != null) {
            city = referenceData
                    .city(homeCityId)
                    .orElseThrow(() -> ApiException.badRequest("CITY_UNKNOWN", "Unknown city id " + homeCityId));
        }
        if (displayName != null && displayName.isBlank()) {
            throw ApiException.badRequest("DISPLAY_NAME_BLANK", "Display name cannot be blank");
        }
        users.updateProfile(
                userId,
                displayName == null ? user.displayName() : displayName.strip(),
                bio == null ? user.bio() : (bio.isBlank() ? null : bio.strip()),
                newHandle,
                city == null ? null : city.id(),
                city == null ? null : city.lat(),
                city == null ? null : city.lng(),
                Instant.now(clock));
        if (avatarMediaId != null) {
            users.setAvatar(userId, avatarMediaId, Instant.now(clock));
        }
        if (showGigStats != null) {
            users.setShowGigStats(userId, showGigStats, Instant.now(clock));
        }
        return me(userId);
    }

    public Me replaceGenres(UUID userId, List<UserRepository.GenrePreference> preferences) {
        current(userId);
        if (preferences.size() < MIN_GENRES || preferences.size() > MAX_GENRES) {
            throw ApiException.badRequest("GENRES_COUNT", "Choose between 1 and 8 genres");
        }
        Set<Short> seen = new HashSet<>();
        for (UserRepository.GenrePreference p : preferences) {
            if (!seen.add(p.genreId())) {
                throw ApiException.badRequest("GENRES_DUPLICATE", "Genre " + p.genreId() + " is listed twice");
            }
            if (p.weight() < 0 || p.weight() > 1) {
                throw ApiException.badRequest("GENRES_WEIGHT", "Weights must be between 0 and 1");
            }
            if (referenceData.genre(p.genreId()).isEmpty()) {
                throw ApiException.badRequest("GENRE_UNKNOWN", "Unknown genre id " + p.genreId());
            }
        }
        users.replaceGenres(userId, preferences, Instant.now(clock));
        return me(userId);
    }

    public Me completeOnboarding(UUID userId) {
        current(userId);
        users.markOnboarded(userId, Instant.now(clock));
        return me(userId);
    }

    /**
     * Soft delete + revoke every session + {@link UserDeleted}; purge after the 7-day grace period.
     * The password check shares login's per-email limit: without it, a stolen access token
     * could be used to guess the account's password at Argon2 speed.
     */
    public void delete(UUID userId, String password) {
        UserRecord user = current(userId);
        rateLimiter.enforce("auth:email:" + user.email(), authLimits.perEmailPerMinute(), Duration.ofMinutes(1));
        if (password.length() > MAX_PASSWORD_LENGTH) { // never Argon2-hash an unbounded string
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Password is incorrect");
        }
        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Password is incorrect");
        }
        Instant now = Instant.now(clock);
        users.softDelete(userId, now);
        refreshTokens.revokeAllForUser(userId, now);
        events.publishEvent(new UserDeleted(userId, now));
    }

    private @Nullable City city(@Nullable Integer id) {
        return id == null ? null : referenceData.city(id).orElse(null);
    }
}
