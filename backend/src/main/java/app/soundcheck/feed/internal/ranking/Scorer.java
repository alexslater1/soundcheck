package app.soundcheck.feed.internal.ranking;

/**
 * The ranking score: {@code score = σ(bias + Σ wᵢ·fᵢ)}, the predicted probability of a
 * positive engagement. A linear model on purpose: every score splits into one contribution per feature
 * ({@code wᵢ·fᵢ}), which is what "Why this post?" and the console show, and the Python lab can learn
 * the weights with the same arithmetic ({@code soundcheck_lab.scorer}; the parity test holds the two
 * together).
 */
public final class Scorer {

    /**
     * @param logit         {@code bias + Σ wᵢ·fᵢ}
     * @param score         {@code σ(logit)}, in (0, 1)
     * @param contributions {@code wᵢ·fᵢ} per feature, in schema order
     */
    public record Scored(double logit, double score, double[] contributions) {}

    private Scorer() {}

    public static Scored score(RankingConfig config, FeatureVector features) {
        double[] contributions = new double[features.size()];
        double logit = config.bias();
        for (int i = 0; i < contributions.length; i++) {
            double contribution = config.weight(i) * features.get(i);
            // A negative weight times zero is -0.0; shown to a person, that should read as 0.
            contributions[i] = contribution == 0 ? 0.0 : contribution;
            logit += contributions[i];
        }
        return new Scored(logit, sigmoid(logit), contributions);
    }

    /** The logistic function, written so that a large |z| cannot overflow {@code exp}. */
    static double sigmoid(double z) {
        if (z >= 0) {
            return 1.0 / (1.0 + Math.exp(-z));
        }
        double e = Math.exp(z);
        return e / (1.0 + e);
    }
}
