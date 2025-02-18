package app.soundcheck.identity.internal;

import app.soundcheck.catalog.City;
import app.soundcheck.catalog.ReferenceData;
import app.soundcheck.identity.UserDeleted;
import app.soundcheck.identity.internal.auth.AuthService;
import app.soundcheck.identity.internal.auth.RefreshTokenRepository;
import app.soundcheck.shared.web.ApiException;
import java.time.Clock;
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

    /** Everything {@code GET /me} shows; counts are zero until social/content exist. */
    public record Me(UserRecord user, @Nullable City homeCity, List<UserRepository.GenrePreference> genres) {}

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final ReferenceData referenceData;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public MeService(
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            ReferenceData referenceData,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher events,
            Clock clock) {
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
        return new Me(user, city(user.homeCityId()), users.genres(userId));
    }

    @Transactional(readOnly = true)
    public Optional<Me> profile(String handle) {
        return users.findByHandle(handle.strip().toLowerCase(Locale.ROOT))
                .filter(u -> !u.deleted())
                .map(u -> new Me(u, city(u.homeCityId()), users.genres(u.id())));
    }

    public Me update(
            UUID userId,
            @Nullable String displayName,
            @Nullable String bio,
            @Nullable String handle,
            @Nullable Integer homeCityId) {
        UserRecord user = current(userId);
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
        users.updateProfile(
                userId,
                displayName == null ? user.displayName() : displayName.strip(),
                bio == null ? user.bio() : (bio.isBlank() ? null : bio.strip()),
                newHandle,
                city == null ? null : city.id(),
                city == null ? null : city.lat(),
                city == null ? null : city.lng(),
                Instant.now(clock));
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

    /** Soft delete + revoke every session + {@link UserDeleted}; purge after the 7-day grace period. */
    public void delete(UUID userId, String password) {
        UserRecord user = current(userId);
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
