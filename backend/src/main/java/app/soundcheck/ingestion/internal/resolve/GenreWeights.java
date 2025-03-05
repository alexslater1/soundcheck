package app.soundcheck.ingestion.internal.resolve;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Genre weight maths shared by entity resolution and enrichment. */
public final class GenreWeights {

    private GenreWeights() {}

    /**
     * Keeps the {@code top} heaviest genres and scales their weights to sum 1 (ties broken by genre id,
     * so the result is deterministic). Empty in, empty out.
     */
    public static Map<Short, Float> topNormalised(Map<Short, Float> raw, int top) {
        Map<Short, Float> kept = new LinkedHashMap<>();
        raw.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<Short, Float>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(top)
                .forEach(e -> kept.put(e.getKey(), e.getValue()));
        float sum = 0;
        for (float weight : kept.values()) {
            sum += weight;
        }
        if (sum > 0) {
            float total = sum;
            kept.replaceAll((genre, weight) -> weight / total);
        }
        return kept;
    }
}
