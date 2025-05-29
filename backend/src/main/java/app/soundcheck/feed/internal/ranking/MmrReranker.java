package app.soundcheck.feed.internal.ranking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Diversity re-ranking: maximal marginal relevance. The scorer alone would put ten clips by
 * one author, or of one artist, at the top if they all score well. Here the list is built one item at a
 * time, each time taking the candidate with the best
 * {@code λ·score − (1−λ)·(similarity to what was just picked)}.
 *
 * <ul>
 *   <li>Similarity: 1.0 same author, 0.7 a shared tagged artist, 0.4 same gig, else 0.
 *   <li>Only the last {@value #WINDOW} picks are compared, so the work is O(candidates · size · 5).
 *   <li>With λ = 1 the result is the plain score order.
 *   <li>With λ &lt; 1 an author is not repeated within three consecutive items as long as any
 *       candidate by someone else is left. The MMR term alone does not guarantee that (a high enough
 *       score outweighs the penalty when λ is close to 1), so it is a rule.
 * </ul>
 */
public final class MmrReranker {

    static final int WINDOW = 5;
    /** An author may appear once in this many consecutive items. */
    static final int AUTHOR_SPACING = 3;

    /** What the re-ranker needs to know about a scored post. */
    public record Item(UUID postId, double score, UUID authorId, Set<UUID> artistIds, @Nullable UUID gigId) {}

    private MmrReranker() {}

    /** The first {@code size} items of the diversified order. */
    public static List<Item> rerank(List<Item> candidates, double lambda, int size) {
        List<Item> byScore = new ArrayList<>(candidates);
        byScore.sort(Comparator.comparingDouble(Item::score).reversed().thenComparing(Item::postId));
        int wanted = Math.min(size, byScore.size());
        if (lambda >= 1) {
            return List.copyOf(byScore.subList(0, wanted));
        }
        List<Item> selected = new ArrayList<>(wanted);
        boolean[] taken = new boolean[byScore.size()];
        while (selected.size() < wanted) {
            int best = -1;
            double bestValue = Double.NEGATIVE_INFINITY;
            int bestSpaced = -1;
            double bestSpacedValue = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < byScore.size(); i++) {
                if (taken[i]) {
                    continue;
                }
                Item candidate = byScore.get(i);
                double value = lambda * candidate.score() - (1 - lambda) * maxSimilarity(candidate, selected);
                // Strictly greater: among equals the higher score (earlier in the list) wins.
                if (value > bestValue) {
                    best = i;
                    bestValue = value;
                }
                if (!authorJustShown(candidate, selected) && value > bestSpacedValue) {
                    bestSpaced = i;
                    bestSpacedValue = value;
                }
            }
            int pick = bestSpaced >= 0 ? bestSpaced : best;
            taken[pick] = true;
            selected.add(byScore.get(pick));
        }
        return selected;
    }

    /** 1.0 same author; 0.7 a shared tagged artist; 0.4 same gig; else 0. */
    static double similarity(Item a, Item b) {
        if (a.authorId().equals(b.authorId())) {
            return 1.0;
        }
        for (UUID artist : a.artistIds()) {
            if (b.artistIds().contains(artist)) {
                return 0.7;
            }
        }
        if (a.gigId() != null && a.gigId().equals(b.gigId())) {
            return 0.4;
        }
        return 0;
    }

    private static double maxSimilarity(Item candidate, List<Item> selected) {
        double max = 0;
        for (int i = Math.max(0, selected.size() - WINDOW); i < selected.size(); i++) {
            max = Math.max(max, similarity(candidate, selected.get(i)));
        }
        return max;
    }

    /** True when the candidate's author wrote one of the last two picks. */
    static boolean authorJustShown(Item candidate, List<Item> selected) {
        for (int i = Math.max(0, selected.size() - (AUTHOR_SPACING - 1)); i < selected.size(); i++) {
            if (selected.get(i).authorId().equals(candidate.authorId())) {
                return true;
            }
        }
        return false;
    }
}
