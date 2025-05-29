package app.soundcheck.feed.internal.ranking;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Genre vectors: a user's taste and a post's sound as genre id → weight, compared by
 * cosine similarity.
 */
public final class GenreProfile {

    /** How much the genres a user chose count against the genres their engagement suggests. */
    static final double EXPLICIT_WEIGHT = 0.6;

    static final double IMPLICIT_WEIGHT = 0.4;

    private GenreProfile() {}

    /**
     * The user's blended genre vector: {@code 0.6·explicit + 0.4·implicit}, scaled to length 1. Empty when
     * nothing is known about the user's taste.
     */
    public static Map<Short, Double> blend(Map<Short, Double> explicit, Map<Short, Double> implicit) {
        Map<Short, Double> blended = new HashMap<>();
        explicit.forEach((genre, weight) -> blended.merge(genre, EXPLICIT_WEIGHT * weight, Double::sum));
        implicit.forEach((genre, weight) -> blended.merge(genre, IMPLICIT_WEIGHT * weight, Double::sum));
        blended.values().removeIf(weight -> weight <= 0);
        double length = length(blended);
        if (length == 0) {
            return Map.of();
        }
        blended.replaceAll((genre, weight) -> weight / length);
        return blended;
    }

    /** The {@code n} strongest genres of a vector, strongest first (ties: lower id first). */
    public static List<Short> top(Map<Short, Double> vector, int n) {
        return vector.entrySet().stream()
                .sorted(Map.Entry.<Short, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(n)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** A post's genre vector: the mean of its tagged artists' genre weights. */
    public static Map<Short, Double> mean(List<Map<Short, Double>> artistGenres) {
        Map<Short, Double> mean = new HashMap<>();
        if (artistGenres.isEmpty()) {
            return mean;
        }
        for (Map<Short, Double> genres : artistGenres) {
            genres.forEach((genre, weight) -> mean.merge(genre, weight, Double::sum));
        }
        mean.replaceAll((genre, sum) -> sum / artistGenres.size());
        return mean;
    }

    /** Cosine similarity in [0, 1] (weights are never negative); 0 when either vector is empty. */
    public static double cosine(Map<Short, Double> a, Map<Short, Double> b) {
        double lengths = length(a) * length(b);
        if (lengths == 0) {
            return 0;
        }
        double dot = 0;
        for (Map.Entry<Short, Double> entry : a.entrySet()) {
            dot += entry.getValue() * b.getOrDefault(entry.getKey(), 0.0);
        }
        return dot / lengths;
    }

    private static double length(Map<Short, Double> vector) {
        double squares = 0;
        for (double weight : vector.values()) {
            squares += weight * weight;
        }
        return Math.sqrt(squares);
    }
}
