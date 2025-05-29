package app.soundcheck.signals.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.soundcheck.signals.internal.AffinityRepository.Key;
import app.soundcheck.signals.internal.AffinityRepository.Stored;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The affinity rules, {@code score ← clamp(score·decay + Δ)} with a 14-day half-life. */
class AffinityMathTest {

    static final Instant T0 = Instant.parse("2026-10-01T12:00:00Z");

    @Test
    void aScoreHalvesEveryFourteenDaysWithoutNewEngagement() {
        assertThat(AffinityMath.decayed(0.8, T0, T0)).isEqualTo(0.8);
        assertThat(AffinityMath.decayed(0.8, T0, T0.plus(Duration.ofDays(14)))).isCloseTo(0.4, within(1e-12));
        assertThat(AffinityMath.decayed(0.8, T0, T0.plus(Duration.ofDays(28)))).isCloseTo(0.2, within(1e-12));
        assertThat(AffinityMath.decayed(0.8, T0, T0.plus(Duration.ofDays(7))))
                .isCloseTo(0.8 / Math.sqrt(2), within(1e-12));
        // A clock that went backwards does not inflate the score.
        assertThat(AffinityMath.decayed(0.8, T0, T0.minusSeconds(60))).isEqualTo(0.8);
    }

    @Test
    void newEngagementIsAddedToTheDecayedScoreAndTheResultStaysBetweenZeroAndOne() {
        Instant later = T0.plus(Duration.ofDays(14));
        assertThat(AffinityMath.next(0.4, T0, later, 0.1)).isCloseTo(0.3, within(1e-12));
        assertThat(AffinityMath.next(0.95, T0, T0, 0.3)).isEqualTo(1.0);
        assertThat(AffinityMath.next(0.1, T0, T0, -0.2)).isZero();
    }

    @Test
    void whatWasDoneToOnePostIsSummedAndBounded() {
        assertThat(AffinityMath.deltaFor(Map.of("LIKE", 1L, "COMPLETE", 1L))).isCloseTo(0.15, within(1e-12));
        // A like taken back is worth nothing; so is any number of likes and unlikes in pairs.
        assertThat(AffinityMath.deltaFor(Map.of("LIKE", 3L, "UNLIKE", 3L))).isCloseTo(0, within(1e-12));
        assertThat(AffinityMath.deltaFor(Map.of("COMMENT", 50L))).isEqualTo(AffinityMath.MAX_PER_POST);
        assertThat(AffinityMath.deltaFor(Map.of("HIDE", 5L))).isEqualTo(-AffinityMath.MAX_PER_POST);
        assertThat(AffinityMath.deltaFor(Map.of("IMPRESSION", 9L))).isZero();
    }

    @Test
    void aScoreThatDoesNotExistYetIsOnlyCreatedByPositiveEngagement() {
        UUID user = UUID.randomUUID();
        Key<String> liked = new Key<>(user, "liked");
        Key<String> hidden = new Key<>(user, "hidden");
        Key<String> known = new Key<>(user, "known");
        Map<Key<String>, Stored> stored = Map.of(known, new Stored(0.4, T0));

        Map<Key<String>, Double> next = AffinityRollup.next(
                stored, Map.of(liked, 0.1, hidden, -0.2, known, -0.1), T0.plus(Duration.ofDays(14)));

        assertThat(next).containsOnlyKeys(liked, known);
        assertThat(next.get(liked)).isEqualTo(0.1);
        assertThat(next.get(known)).isCloseTo(0.1, within(1e-12));
    }
}
