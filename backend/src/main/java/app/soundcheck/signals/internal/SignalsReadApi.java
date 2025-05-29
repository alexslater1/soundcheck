package app.soundcheck.signals.internal;

import app.soundcheck.signals.Affinities;
import app.soundcheck.signals.EngagementPriors;
import app.soundcheck.signals.internal.AffinityRepository.Stored;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link Affinities} and {@link EngagementPriors} for the feed. Scores are decayed to the present when
 * they are read: the rollup only rewrites a score when there is new engagement, so a stored score can
 * be weeks old.
 */
@Component
@Transactional(readOnly = true)
class SignalsReadApi implements Affinities, EngagementPriors {

    private final AffinityRepository affinities;
    private final PostStatsRepository stats;
    private final Clock clock;

    SignalsReadApi(AffinityRepository affinities, PostStatsRepository stats, Clock clock) {
        this.affinities = affinities;
        this.stats = stats;
        this.clock = clock;
    }

    @Override
    public Map<UUID, Double> artists(UUID userId, int limit) {
        return strongest(affinities.artistsOf(userId), limit);
    }

    @Override
    public Map<UUID, Double> authors(UUID userId, int limit) {
        return strongest(affinities.authorsOf(userId), limit);
    }

    @Override
    public Map<Short, Double> genres(UUID userId) {
        return strongest(affinities.genresOf(userId), Integer.MAX_VALUE);
    }

    @Override
    public Prior current() {
        return stats.prior().orElse(Prior.NONE);
    }

    private <T> Map<T, Double> strongest(Map<T, Stored> stored, int limit) {
        Instant now = Instant.now(clock);
        Map<T, Double> strongest = new LinkedHashMap<>();
        stored.entrySet().stream()
                .map(e -> Map.entry(
                        e.getKey(),
                        AffinityMath.decayed(e.getValue().score(), e.getValue().updatedAt(), now)))
                .sorted(Map.Entry.<T, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .forEach(e -> strongest.put(e.getKey(), e.getValue()));
        return strongest;
    }
}
