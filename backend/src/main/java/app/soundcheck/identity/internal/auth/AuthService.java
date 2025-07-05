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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Register / login / refresh / logout. Refresh tokens rotate on every
 * use within a {@code family}; presenting an already-used token revokes the whole family (likely theft),
 * unless its answer was simply lost on the way.
 */
@Service
@Transactional
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** How many simultaneous retries of one lost answer are sorted out before it counts as reuse. */
    static final int LOST_ANSWER_RETRIES = 3;

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
            return presentedAgain(presented, client, userAgent, now);
        }
        if (!presented.expiresAt().isAfter(now)) {
            throw ApiException.unauthorized("REFRESH_EXPIRED", "Refresh token has expired; sign in again");
        }
        UserRecord user = activeUser(presented);
        UUID newId = Ids.newId();
        // Atomic conditional update: if two requests race with the same token, exactly one wins the
        // rotation; the other finds the token rotated a moment ago and follows the rule for a token
        // presented again, closing the race the SELECT alone leaves.
        if (refreshTokens.revoke(presented.id(), newId, now) == 0) {
            return presentedAgain(refreshTokens.findById(presented.id()).orElseThrow(), client, userAgent, now);
        }
        return startSession(user, presented.familyId(), newId, client, userAgent, now);
    }

    /**
     * A token presented after it was rotated. If its answer may simply have been lost on the way
     * — it was rotated less than {@code refresh-grace} ago, and the token that replaced it has never been
     * presented — that unused successor is revoked and a new one issued in the same family, and the
     * presented token now points at it (so a second lost answer can be asked for again within the same
     * period, which runs from the first rotation). Anything else is reuse, likely theft: the whole family
     * is revoked. A successor that was presented means a second holder of the family; a token
     * revoked without a successor was signed out (logout, reuse, account deletion), which is final.
     */
    private Session presentedAgain(
            RefreshTokenRepository.Row presented, String client, @Nullable String userAgent, Instant now) {
        RefreshTokenRepository.Row current = presented;
        for (int attempt = 0; attempt < LOST_ANSWER_RETRIES; attempt++) {
            UUID successor = current.replacedBy();
            Instant rotatedAt = current.revokedAt();
            if (successor == null || rotatedAt == null || rotatedAt.isBefore(now.minus(settings.refreshGrace()))) {
                break;
            }
            UUID newId = Ids.newId();
            // Conditional: only a successor nobody has presented can be replaced.
            if (refreshTokens.revoke(successor, null, now) == 1) {
                refreshTokens.replaceSuccessor(current.id(), newId);
                log.info(
                        "Refresh token of family {} presented again {} ms after its rotation; its answer was lost, rotated again",
                        current.familyId(),
                        Duration.between(rotatedAt, now).toMillis());
                return startSession(activeUser(current), current.familyId(), newId, client, userAgent, now);
            }
            // The successor is gone. If a simultaneous retry of this same token replaced it, this token now
            // points at that retry's token: look again. If it is still the same successor, it was presented.
            RefreshTokenRepository.Row reread =
                    refreshTokens.findById(current.id()).orElseThrow();
            if (successor.equals(reread.replacedBy())) {
                break;
            }
            current = reread;
        }
        refreshTokens.revokeFamily(presented.familyId(), now);
        log.warn(
                "Refresh token reuse: family {} of user {} revoked (rotated at {})",
                presented.familyId(),
                presented.userId(),
                presented.revokedAt());
        throw ApiException.unauthorized("REFRESH_REUSED", "Refresh token was already used; sign in again");
    }

    private UserRecord activeUser(RefreshTokenRepository.Row token) {
        return users.findById(token.userId())
                .filter(u -> !u.deleted())
                .orElseThrow(() -> ApiException.unauthorized("REFRESH_INVALID", "Refresh token is not valid"));
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
