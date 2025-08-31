package app.soundcheck.feed.internal.ranking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The numbers the console compares rankings with. Pure functions; the hand-computed cases in
 * {@code contracts/ranking/metric-cases.json} hold this class and the Python lab's {@code evaluate} to the
 * same answers.
 *
 * <ul>
 *   <li>Two lists against each other (preview): overlap@k and Kendall's τ-b.
 *   <li>Scores against what users did (replay): AUC, log-loss and NDCG@k.
 * </ul>
 */
public final class RankingMetrics {

    /** Probabilities are clipped to [ε, 1 − ε] before the logarithm, so one confident miss is not infinite. */
    public static final double LOG_LOSS_EPSILON = 1e-15;

    private RankingMetrics() {}

    /**
     * The share of the first {@code k} items of {@code a} that are also among the first {@code k} of
     * {@code b}: 1 for the same top k, 0 for none in common. Divided by the longer of the two cut lists, so
     * a short list cannot score 1 against a long one. Null when both are empty.
     */
    public static <T> @Nullable Double overlapAt(int k, List<T> a, List<T> b) {
        List<T> topA = a.subList(0, Math.min(k, a.size()));
        List<T> topB = b.subList(0, Math.min(k, b.size()));
        int denominator = Math.max(topA.size(), topB.size());
        if (denominator == 0) {
            return null;
        }
        Set<T> inB = new HashSet<>(topB);
        long common = topA.stream().filter(inB::contains).count();
        return (double) common / denominator;
    }

    /**
     * Kendall's τ-b between two scorings of the same items ({@code x[i]} and {@code y[i]} belong to item
     * i): +1 when they order every pair the same way, −1 when they order every pair the opposite way.
     * Ties (in either) count as neither agreeing nor disagreeing, and τ-b's denominator corrects for them:
     * {@code (C − D) / √((n₀ − n₁)(n₀ − n₂))}, with n₀ all pairs and n₁, n₂ the pairs tied in x and in y.
     * O(n²), which is plenty for a preview of at most 50 items.
     *
     * @return null with fewer than two items, or when either side ties everything (τ is undefined)
     */
    public static @Nullable Double kendallTauB(double[] x, double[] y) {
        if (x.length != y.length) {
            throw new IllegalArgumentException("x and y must score the same items");
        }
        int n = x.length;
        long concordant = 0;
        long discordant = 0;
        long tiedX = 0;
        long tiedY = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int dx = Double.compare(x[i], x[j]);
                int dy = Double.compare(y[i], y[j]);
                if (dx == 0) {
                    tiedX++;
                }
                if (dy == 0) {
                    tiedY++;
                }
                if (dx != 0 && dy != 0) {
                    if (dx == dy) {
                        concordant++;
                    } else {
                        discordant++;
                    }
                }
            }
        }
        long pairs = (long) n * (n - 1) / 2;
        double denominator = Math.sqrt((double) (pairs - tiedX) * (pairs - tiedY));
        if (n < 2 || denominator == 0) {
            return null;
        }
        return (concordant - discordant) / denominator;
    }

    /**
     * The area under the ROC curve: the probability that a random positive scores above a random negative,
     * counted pair by pair with ties as half (Mann–Whitney U / (positives · negatives)). Computed from
     * ranks in O(n log n): sort by score, give tied scores their average rank, sum the positives' ranks.
     *
     * @return null without at least one positive and one negative
     */
    public static @Nullable Double auc(double[] scores, boolean[] positive) {
        int n = scores.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, Comparator.comparingDouble(i -> scores[i]));
        double positiveRankSum = 0;
        long positives = 0;
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && scores[order[j + 1]] == scores[order[i]]) {
                j++;
            }
            double averageRank = (i + j) / 2.0 + 1; // ranks count from 1
            for (int k = i; k <= j; k++) {
                if (positive[order[k]]) {
                    positiveRankSum += averageRank;
                    positives++;
                }
            }
            i = j + 1;
        }
        long negatives = n - positives;
        if (positives == 0 || negatives == 0) {
            return null;
        }
        double u = positiveRankSum - positives * (positives + 1) / 2.0;
        return u / ((double) positives * negatives);
    }

    /**
     * The mean binary cross-entropy of the predicted probabilities against the labels, natural log, with
     * each probability clipped to [{@value #LOG_LOSS_EPSILON}, 1 − {@value #LOG_LOSS_EPSILON}].
     *
     * @return null for no items
     */
    public static @Nullable Double logLoss(double[] probabilities, boolean[] positive) {
        if (probabilities.length == 0) {
            return null;
        }
        double sum = 0;
        for (int i = 0; i < probabilities.length; i++) {
            double p = Math.min(1 - LOG_LOSS_EPSILON, Math.max(LOG_LOSS_EPSILON, probabilities[i]));
            sum -= positive[i] ? Math.log(p) : Math.log(1 - p);
        }
        return sum / probabilities.length;
    }

    /**
     * NDCG@k of one session with binary relevance: the items are put in order of {@code scores} (highest
     * first; equal scores keep their given order, which is the order they were shown in), the gain of the
     * item at rank r (from 0) is {@code 1 / log₂(r + 2)} when it was positive, and the sum is divided by the
     * best possible sum (every positive first).
     *
     * @return null when the session has no positive (there is nothing to rank well)
     */
    public static @Nullable Double ndcgAt(int k, double[] scores, boolean[] positive) {
        int n = scores.length;
        List<Integer> order = new ArrayList<>(n);
        int positives = 0;
        for (int i = 0; i < n; i++) {
            order.add(i);
            if (positive[i]) {
                positives++;
            }
        }
        if (positives == 0) {
            return null;
        }
        // A stable sort: ties stay in the order shown.
        order.sort(Comparator.comparingDouble((Integer i) -> scores[i]).reversed());
        double dcg = 0;
        for (int r = 0; r < Math.min(k, n); r++) {
            if (positive[order.get(r)]) {
                dcg += gain(r);
            }
        }
        double ideal = 0;
        for (int r = 0; r < Math.min(k, positives); r++) {
            ideal += gain(r);
        }
        return dcg / ideal;
    }

    private static double gain(int rank) {
        return 1 / (Math.log(rank + 2) / Math.log(2));
    }
}
