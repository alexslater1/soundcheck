package app.soundcheck.identity.internal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.soundcheck.identity.ProfileVisibility;
import app.soundcheck.identity.internal.UserRecord;
import app.soundcheck.identity.internal.UserRepository;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.RateLimitProperties;
import app.soundcheck.shared.web.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The refresh grace rule on {@link AuthService} alone, over an in-memory token table with the
 * same conditional updates as {@link RefreshTokenRepository}. {@code RefreshGraceIT} and {@code
 * RefreshRaceIT} check the same rule through HTTP against PostgreSQL.
 */
class RefreshGraceTest {

    static final Duration GRACE = Duration.ofSeconds(60);

    final InMemoryTokens tokens = new InMemoryTokens();
    final TestClock clock = new TestClock();
    final UUID userId = UUID.randomUUID();
    AuthService auth;

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class);
        when(users.findById(any()))
                .thenReturn(Optional.of(new UserRecord(
                        userId,
                        "fan@example.com",
                        "fan",
                        "Fan",
                        null,
                        "USER",
                        "hash",
                        null,
                        null,
                        Instant.EPOCH,
                        null,
                        ProfileVisibility.PUBLIC)));
        AuthProperties.Jwt settings = new AuthProperties.Jwt(
                null, null, "soundcheck", "soundcheck-app", Duration.ofMinutes(15), Duration.ofDays(30), GRACE);
        auth = new AuthService(
                users,
                tokens,
                mock(PasswordEncoder.class),
                mock(PasswordPolicy.class),
                new JwtIssuer(JwtKeys.generate(), settings, clock),
                new AuthProperties(settings),
                mock(RateLimiter.class),
                mock(RateLimitProperties.class),
                clock);
    }

    @Test
    void anAnswerLostOnTheWayCanBeAskedForAgainAndTheSessionGoesOn() {
        String first = signIn();
        String lost = refresh(first);

        clock.advance(Duration.ofSeconds(30));
        String again = refresh(first);
        String next = refresh(again);

        assertThat(next).isNotNull();
        assertThat(reused(lost)).isTrue();
        assertThat(reused(next)).isTrue(); // presenting the lost one revoked the family
    }

    @Test
    void twoLostAnswersInARowStillLeaveTheSession() {
        String first = signIn();
        refresh(first);
        clock.advance(Duration.ofSeconds(10));
        refresh(first);
        clock.advance(Duration.ofSeconds(10));

        assertThat(refresh(refresh(first))).isNotNull();
    }

    @Test
    void afterTheGracePeriodItIsReuseAndTheFamilyIsRevoked() {
        String first = signIn();
        String second = refresh(first);

        clock.advance(GRACE.plusSeconds(1));

        assertThat(reused(first)).isTrue();
        assertThat(reused(second)).isTrue();
    }

    @Test
    void theGracePeriodRunsFromTheFirstRotationNotTheLastRetry() {
        String first = signIn();
        refresh(first);
        clock.advance(Duration.ofSeconds(40));
        String retried = refresh(first);
        clock.advance(Duration.ofSeconds(30));

        assertThat(reused(first)).isTrue();
        assertThat(reused(retried)).isTrue();
    }

    @Test
    void onceTheReplacementHasBeenPresentedTheOldTokenIsTheftEvenWithinTheGracePeriod() {
        String first = signIn();
        String third = refresh(refresh(first));

        assertThat(reused(first)).isTrue();
        assertThat(reused(third)).isTrue();
    }

    @Test
    void signingOutIsFinalEvenWithinTheGracePeriod() {
        String first = signIn();
        auth.logout(refresh(first));

        assertThat(reused(first)).isTrue();
    }

    @Test
    void twoRequestsRacingWithOneTokenBothGetASessionAndExactlyOneTokenSurvives() {
        String first = signIn();
        String[] winner = new String[1];
        // The other request rotates the token between this one's read and its conditional update.
        tokens.beforeNextRevoke = () -> winner[0] = refresh(first);

        String loser = refresh(first);

        assertThat(List.of(winner[0], loser)).filteredOn(this::live).hasSize(1);
        assertThat(tokens.rows.values()).filteredOn(r -> !r.revoked()).hasSize(1);
    }

    @Test
    void threeRequestsRacingWithOneTokenStillLeaveOneSession() {
        String first = signIn();
        String[] others = new String[2];
        tokens.beforeNextRevoke = () -> {
            tokens.beforeNextRevoke = () -> others[1] = refresh(first);
            others[0] = refresh(first);
        };

        String last = refresh(first);

        assertThat(List.of(others[0], others[1], last)).filteredOn(this::live).hasSize(1);
    }

    /** A signed-in session's first refresh token, as login would store it. */
    private String signIn() {
        String raw = AuthService.newRefreshToken();
        tokens.insert(
                new RefreshTokenRepository.Row(
                        UUID.randomUUID(),
                        userId,
                        UUID.randomUUID(),
                        AuthService.hash(raw),
                        clock.instant().plus(Duration.ofDays(30)),
                        null,
                        null,
                        "android"),
                null,
                clock.instant());
        return raw;
    }

    private String refresh(String token) {
        return auth.refresh(token, "android", null).refreshToken();
    }

    private boolean reused(String token) {
        try {
            auth.refresh(token, "android", null);
            return false;
        } catch (ApiException e) {
            return e.getMessage().contains("already used");
        }
    }

    private boolean live(String token) {
        return tokens.findByHash(AuthService.hash(token)).map(r -> !r.revoked()).orElse(false);
    }

    /** {@link RefreshTokenRepository} in a map, with its conditional updates; no database. */
    static final class InMemoryTokens extends RefreshTokenRepository {

        final Map<UUID, Row> rows = new LinkedHashMap<>();
        /** Runs once, just before the next {@link #revoke}: stands in for a concurrent request. */
        @Nullable
        Runnable beforeNextRevoke;

        InMemoryTokens() {
            super(null);
        }

        @Override
        public void insert(Row row, @Nullable String userAgent, Instant now) {
            rows.put(row.id(), row);
        }

        @Override
        public Optional<Row> findByHash(byte[] hash) {
            return rows.values().stream()
                    .filter(r -> Arrays.equals(r.tokenHash(), hash))
                    .findFirst();
        }

        @Override
        public Optional<Row> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public int revoke(UUID id, @Nullable UUID replacedBy, Instant now) {
            Runnable concurrent = beforeNextRevoke;
            if (concurrent != null) {
                beforeNextRevoke = null;
                concurrent.run();
            }
            Row row = rows.get(id);
            if (row == null || row.revoked()) {
                return 0;
            }
            rows.put(id, with(row, now, replacedBy));
            return 1;
        }

        @Override
        public int replaceSuccessor(UUID id, UUID replacedBy) {
            Row row = rows.get(id);
            if (row == null) {
                return 0;
            }
            rows.put(id, with(row, row.revokedAt(), replacedBy));
            return 1;
        }

        @Override
        public int revokeFamily(UUID familyId, Instant now) {
            int n = 0;
            for (Row row : List.copyOf(rows.values())) {
                if (row.familyId().equals(familyId) && !row.revoked()) {
                    rows.put(row.id(), with(row, now, row.replacedBy()));
                    n++;
                }
            }
            return n;
        }

        private static Row with(Row r, @Nullable Instant revokedAt, @Nullable UUID replacedBy) {
            return new Row(
                    r.id(), r.userId(), r.familyId(), r.tokenHash(), r.expiresAt(), revokedAt, replacedBy, r.client());
        }
    }

    /** A clock the test moves. */
    static final class TestClock extends Clock {

        private Instant now = Instant.parse("2026-10-03T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
