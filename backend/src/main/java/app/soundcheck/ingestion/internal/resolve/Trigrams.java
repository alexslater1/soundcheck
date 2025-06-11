package app.soundcheck.ingestion.internal.resolve;

import app.soundcheck.catalog.NameNormaliser;
import java.util.HashSet;
import java.util.Set;

/**
 * Name similarity in Java, defined like PostgreSQL's {@code pg_trgm} {@code similarity()} so a decision
 * made here agrees with one made in SQL: both names are normalised ({@link NameNormaliser}), each word
 * is padded with two spaces in front and one behind, and the score is the share of three-letter pieces
 * the two names have in common (|A ∩ B| / |A ∪ B|, 0 to 1). "radiohead" and "radiohed" score 0.67;
 * "the windmill" and "windmill brixton" 0.5.
 */
public final class Trigrams {

    private Trigrams() {}

    public static double similarity(String a, String b) {
        Set<String> left = trigrams(NameNormaliser.normalise(a));
        Set<String> right = trigrams(NameNormaliser.normalise(b));
        if (left.isEmpty() && right.isEmpty()) {
            return 0;
        }
        Set<String> common = new HashSet<>(left);
        common.retainAll(right);
        Set<String> all = new HashSet<>(left);
        all.addAll(right);
        return (double) common.size() / all.size();
    }

    static Set<String> trigrams(String normalised) {
        Set<String> out = new HashSet<>();
        for (String word : normalised.split("[^\\p{L}\\p{N}]+")) {
            if (word.isEmpty()) {
                continue;
            }
            String padded = "  " + word + " ";
            for (int i = 0; i + 3 <= padded.length(); i++) {
                out.add(padded.substring(i, i + 3));
            }
        }
        return out;
    }
}
