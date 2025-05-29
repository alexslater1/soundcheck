package app.soundcheck.feed.internal;

import app.soundcheck.feed.internal.CandidateRepository.Candidate;
import app.soundcheck.feed.internal.CandidateRepository.Stats;
import app.soundcheck.feed.internal.ranking.FeatureComputer;
import app.soundcheck.feed.internal.ranking.FeatureComputer.PostFacts;
import app.soundcheck.feed.internal.ranking.FeatureComputer.Scales;
import app.soundcheck.feed.internal.ranking.FeatureSchema;
import app.soundcheck.feed.internal.ranking.FeatureVector;
import app.soundcheck.feed.internal.ranking.GenreProfile;
import app.soundcheck.feed.internal.ranking.RankingConfig;
import app.soundcheck.signals.EngagementPriors.Prior;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Candidate generation and feature extraction: one query for the candidates, then
 * five batched queries for the facts their features need, then the formulas. Six round trips for any
 * number of candidates.
 */
@Component
public class CandidateGenerator {

    /** A candidate with the facts loaded for it and its feature vector. */
    public record Featured(Candidate candidate, PostFacts facts, FeatureVector features) {}

    private final CandidateRepository repository;
    private final FeatureSchema schema;

    CandidateGenerator(CandidateRepository repository, FeatureSchema schema) {
        this.repository = repository;
        this.schema = schema;
    }

    /** The viewer's candidates as of {@code now}, unranked. */
    @Transactional(readOnly = true)
    public List<Candidate> candidates(UserContext viewer, RankingConfig.Params params, Instant now) {
        return repository.candidates(viewer, params, now);
    }

    /** Loads the facts for the candidates and computes each one's features. */
    @Transactional(readOnly = true)
    public List<Featured> features(
            UserContext viewer, List<Candidate> candidates, RankingConfig.Params params, Prior prior, Instant now) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<UUID> postIds = candidates.stream().map(Candidate::postId).toList();
        Map<UUID, List<UUID>> tags = repository.tags(postIds);
        Map<UUID, Map<Short, Double>> genres = repository.genres(
                tags.values().stream().flatMap(List::stream).distinct().toList());
        Map<UUID, Stats> stats = repository.stats(postIds);
        Map<UUID, Double> distances = repository.distancesKm(
                candidates.stream()
                        .map(Candidate::gigId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList(),
                viewer.home());
        Map<UUID, Integer> friends = repository.engagedAmong(postIds, viewer.followedUsers());

        Scales scales = new Scales(params.halfLifeHours(), params.priorStrength(), prior.meanRate(), prior.p99Rate());
        FeatureComputer.Viewer who = viewer.viewer();
        List<Featured> featured = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            List<UUID> artists = tags.getOrDefault(candidate.postId(), List.of());
            Stats counters = stats.getOrDefault(candidate.postId(), Stats.NONE);
            PostFacts facts = new PostFacts(
                    candidate.postId(),
                    candidate.authorId(),
                    candidate.gigId(),
                    candidate.publishedAt(),
                    artists,
                    GenreProfile.mean(artists.stream()
                            .map(artist -> genres.getOrDefault(artist, Map.of()))
                            .toList()),
                    counters.impressions(),
                    counters.likes(),
                    counters.comments(),
                    counters.shares(),
                    counters.completes(),
                    candidate.gigId() == null ? null : distances.get(candidate.gigId()),
                    friends.getOrDefault(candidate.postId(), 0));
            featured.add(new Featured(candidate, facts, FeatureComputer.compute(schema, who, facts, scales, now)));
        }
        return featured;
    }
}
