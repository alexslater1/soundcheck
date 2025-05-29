package app.soundcheck.signals.internal;

import app.soundcheck.content.PostFacts;
import app.soundcheck.content.PostFacts.Facts;
import app.soundcheck.signals.internal.AffinityRepository.Key;
import app.soundcheck.signals.internal.AffinityRepository.Stored;
import app.soundcheck.signals.internal.EventLog.Position;
import app.soundcheck.signals.internal.EventLog.UserPostCount;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns new engagement into affinities: what a user did with a post
 * moves their score for the post's author, for each tagged artist, and for the genres of those artists
 * (in proportion to the genre's weight). {@code score ← clamp(score·decay + Δ)}, see
 * {@link AffinityMath}.
 *
 * <p>Like {@link PostStatsRollup}, one batch is one transaction with its own watermark, so engagement
 * is counted exactly once.
 */
@Service
public class AffinityRollup {

    static final String NAME = "affinity";
    static final int BATCH = 50_000;

    private final EventLog log;
    private final AffinityRepository affinities;
    private final PostFacts posts;

    AffinityRollup(EventLog log, AffinityRepository affinities, PostFacts posts) {
        this.log = log;
        this.affinities = affinities;
        this.posts = posts;
    }

    /**
     * Folds in one batch of the events that arrived up to {@code upTo}; decay is counted up to
     * {@code now}.
     *
     * @return true when more events are waiting after this batch
     */
    @Transactional
    public boolean rollUpBatch(Instant upTo, Instant now) {
        Position from = log.watermarkForUpdate(NAME);
        Optional<Position> end = log.endOfBatch(from, upTo, BATCH);
        if (end.isEmpty()) {
            return false;
        }
        List<UserPostCount> engagement = log.engagementByUserAndPost(from, end.get());

        // What each user did to each post, as one number.
        Map<Key<UUID>, Map<String, Long>> byUserAndPost = new HashMap<>();
        for (UserPostCount count : engagement) {
            byUserAndPost
                    .computeIfAbsent(new Key<>(count.userId(), count.postId()), k -> new HashMap<>())
                    .merge(count.eventType(), count.count(), Long::sum);
        }
        Map<UUID, Facts> facts = new HashMap<>();
        List<UUID> postIds =
                byUserAndPost.keySet().stream().map(Key::target).distinct().toList();
        for (List<UUID> chunk : AffinityRepository.chunks(postIds, 1_000)) {
            facts.putAll(posts.facts(chunk));
        }

        // Spread it over the post's author, artists and genres.
        Map<Key<UUID>, Double> artistDelta = new HashMap<>();
        Map<Key<UUID>, Double> authorDelta = new HashMap<>();
        Map<Key<Short>, Double> genreDelta = new HashMap<>();
        byUserAndPost.forEach((key, counts) -> {
            Facts post = facts.get(key.target());
            double delta = AffinityMath.deltaFor(counts);
            if (post == null || delta == 0) {
                return; // the post is gone, or the engagement cancelled out (like, then unlike)
            }
            UUID userId = key.userId();
            if (!post.authorId().equals(userId)) {
                authorDelta.merge(new Key<>(userId, post.authorId()), delta, Double::sum);
            }
            for (UUID artistId : post.artistIds()) {
                artistDelta.merge(new Key<>(userId, artistId), delta, Double::sum);
            }
            post.genreWeights()
                    .forEach(
                            (genre, weight) -> genreDelta.merge(new Key<>(userId, genre), delta * weight, Double::sum));
        });

        Set<UUID> users = byUserAndPost.keySet().stream().map(Key::userId).collect(Collectors.toSet());
        affinities.saveArtistScores(next(affinities.artistScores(users), artistDelta, now), now);
        affinities.saveAuthorScores(next(affinities.authorScores(users), authorDelta, now), now);
        affinities.saveGenreScores(next(affinities.genreScores(users), genreDelta, now), now);
        log.advance(NAME, end.get());
        return log.endOfBatch(end.get(), upTo, 1).isPresent();
    }

    /**
     * The scores to store: each touched one decayed to now and moved by its delta. A score that does not
     * exist yet is only created by positive engagement.
     */
    static <T> Map<Key<T>, Double> next(Map<Key<T>, Stored> stored, Map<Key<T>, Double> deltas, Instant now) {
        Map<Key<T>, Double> next = new HashMap<>();
        deltas.forEach((key, delta) -> {
            Stored old = stored.get(key);
            if (old == null) {
                if (delta > 0) {
                    next.put(key, AffinityMath.clamp(delta));
                }
            } else {
                next.put(key, AffinityMath.next(old.score(), old.updatedAt(), now, delta));
            }
        });
        return next;
    }
}
