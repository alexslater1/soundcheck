package app.soundcheck.identity.internal.auth;

import app.soundcheck.identity.ProfileVisibility;
import app.soundcheck.identity.internal.UserRecord;
import app.soundcheck.identity.internal.UserRepository;
import app.soundcheck.shared.Ids;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.RateLimitProperties;
import app.soundcheck.shared.web.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Register / login / refresh / logout. Refresh tokens rotate on every
 * use within a {@code family}; presenting an already-used token revokes the whole family (likely theft).
 */
@Service
@Transactional
public class AuthService {

    public static final Pattern HANDLE = Pattern.compile("^[a-z0-9_]{3,30}$");
    public static final Set<String> RESERVED_HANDLES = Set.of(
            "admin",
            "administrator",
            "console",
            "api",
            "soundcheck",
            "root",
            "support",
            "help",
            "me",
            "settings",
            "auth",
            "login",
            "signup",
            "register",
            "system",
            "null",
            "undefined",
            "moderator",
            "mod",
            "staff",
            "official",
            "about",
            "privacy",
            "terms");
    static final Set<String> CLIENTS = Set.of("android", "web", "lab", "test");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final JwtIssuer jwtIssuer;
    private final AuthProperties.Jwt settings;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties.Auth limits;
    private final Clock clock;
    /** Verified for unknown emails so login timing does not reveal whether an account exists. */
    private final String dummyHash;

    public AuthService(
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            JwtIssuer jwtIssuer,
            AuthProperties properties,
            RateLimiter rateLimiter,
            RateLimitProperties rateLimits,
            Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.jwtIssuer = jwtIssuer;
        this.settings = properties.jwt();
        this.rateLimiter = rateLimiter;
        this.limits = rateLimits.auth();
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(newRefreshToken());
    }

    public record Session(String accessToken, long expiresIn, String refreshToken, UserRecord user) {}

    public Session register(
            String email,
            String password,
            String handle,
            String displayName,
            String client,
            @Nullable String userAgent) {
        String normalisedEmail = email.strip().toLowerCase(Locale.ROOT);
        String normalisedHandle = handle.strip().toLowerCase(Locale.ROOT);
        limitPerEmail(normalisedEmail);
        passwordPolicy.validate(password);
        if (!HANDLE.matcher(normalisedHandle).matches()) {
            throw ApiException.badRequest("HANDLE_INVALID", "Handle must be 3–30 characters: a–z, 0–9, _");
        }
        if (RESERVED_HANDLES.contains(normalisedHandle)) {
            throw ApiException.conflict("HANDLE_RESERVED", "That handle is reserved");
        }
        if (users.emailExists(normalisedEmail)) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with that email already exists");
        }
        if (users.handleExists(normalisedHandle)) {
            throw ApiException.conflict("HANDLE_TAKEN", "That handle is taken");
        }
        Instant now = Instant.now(clock);
        UserRecord user = new UserRecord(
                Ids.newId(),
                normalisedEmail,
                normalisedHandle,
                displayName.strip(),
                null,
                "USER",
                passwordEncoder.encode(password),
                null,
                null,
                now,
                null,
                ProfileVisibility.PUBLIC); // public by default
        users.insert(user);
        return startSession(user, Ids.newId(), client, userAgent, now);
    }

    public Session login(String email, String password, String client, @Nullable String userAgent) {
        String normalisedEmail = email.strip().toLowerCase(Locale.ROOT);
        limitPerEmail(normalisedEmail);
        var user = users.findByEmail(normalisedEmail).filter(u -> !u.deleted());
        // Always run one Argon2 verification so timing is the same for unknown and known emails.
        boolean matches = passwordEncoder.matches(
                password, user.map(UserRecord::passwordHash).orElse(dummyHash));
        if (user.isEmpty() || !matches) {
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Email or password is incorrect");
        }
        return startSession(user.get(), Ids.newId(), client, userAgent, Instant.now(clock));
    }

    /**
     * Rotation with reuse detection. The family revocation must survive the 401 we answer
     * with, so {@link ApiException} does not roll this transaction back.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Session refresh(String rawRefreshToken, String client, @Nullable String userAgent) {
        Instant now = Instant.now(clock);
        var presented = refreshTokens
                .findByHash(hash(rawRefreshToken))
                .orElseThrow(() -> ApiException.unauthorized("REFRESH_INVALID", "Refresh token is not valid"));
        if (presented.revoked()) {
            refreshTokens.revokeFamily(presented.familyId(), now);
            throw ApiException.unauthorized("REFRESH_REUSED", "Refresh token was already used; sign in again");
        }
        if (!presented.expiresAt().isAfter(now)) {
            throw ApiException.unauthorized("REFRESH_EXPIRED", "Refresh token has expired; sign in again");
        }
        UserRecord user = users.findById(presented.userId())
                .filter(u -> !u.deleted())
                .orElseThrow(() -> ApiException.unauthorized("REFRESH_INVALID", "Refresh token is not valid"));
        UUID newId = Ids.newId();
        // Atomic conditional update: if two requests race with the same token, exactly one wins the
        // rotation; the loser is treated as reuse (family revoked), closing the race the SELECT alone leaves.
        if (refreshTokens.revoke(presented.id(), newId, now) == 0) {
            refreshTokens.revokeFamily(presented.familyId(), now);
            throw ApiException.unauthorized("REFRESH_REUSED", "Refresh token was already used; sign in again");
        }
        return startSession(user, presented.familyId(), newId, client, userAgent, now);
    }

    /** Revokes the presented token's whole family; silent when the token is unknown. */
    public void logout(@Nullable String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        Instant now = Instant.now(clock);
        refreshTokens.findByHash(hash(rawRefreshToken)).ifPresent(t -> refreshTokens.revokeFamily(t.familyId(), now));
    }

    private Session startSession(
            UserRecord user, UUID familyId, String client, @Nullable String userAgent, Instant now) {
        return startSession(user, familyId, Ids.newId(), client, userAgent, now);
    }

    private Session startSession(
            UserRecord user, UUID familyId, UUID tokenId, String client, @Nullable String userAgent, Instant now) {
        if (!CLIENTS.contains(client)) {
            throw ApiException.badRequest("CLIENT_INVALID", "X-Client must be android or web");
        }
        String raw = newRefreshToken();
        refreshTokens.insert(
                new RefreshTokenRepository.Row(
                        tokenId, user.id(), familyId, hash(raw), now.plus(settings.refreshTtl()), null, null, client),
                userAgent,
                now);
        JwtIssuer.IssuedToken access = jwtIssuer.issue(user.id(), user.role());
        return new Session(access.token(), settings.accessTtl().toSeconds(), raw, user);
    }

    /** 5/min per email across login and register (throws 429). */
    private void limitPerEmail(String email) {
        rateLimiter.enforce("auth:email:" + email, limits.perEmailPerMinute(), Duration.ofMinutes(1));
    }

    /** 256-bit random, base64url without padding. */
    static String newRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] hash(String raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
