package app.soundcheck.feed.internal.ranking;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * One version of the ranking: a weight per feature plus a bias and the parameters of the
 * stages around the scorer. Instances are always valid: {@link #of} refuses anything else.
 */
public final class RankingConfig {

    public static final String BIAS = "bias";

    /**
     * The stage parameters, plus {@code authorCapPerSession}: at most this many posts by one author in
     * a session, as long as enough other authors have posts.
     */
    public record Params(
            double halfLifeHours,
            double priorStrength,
            double diversityLambda,
            double explorationRate,
            int sessionSize,
            int pageSize,
            Map<String, Integer> candidateCaps,
            double localRadiusKm,
            int seenWindowDays,
            int authorCapPerSession) {

        public Params {
            candidateCaps = candidateCaps == null ? Map.of() : Map.copyOf(candidateCaps);
        }

        /** The parameter names every stored config must have. */
        public static final List<String> NAMES = List.of(
                "halfLifeHours",
                "priorStrength",
                "diversityLambda",
                "explorationRate",
                "sessionSize",
                "pageSize",
                "candidateCaps",
                "localRadiusKm",
                "seenWindowDays",
                "authorCapPerSession");

        /** Which of {@link #NAMES} are absent from the given names. */
        public static List<String> missingFrom(java.util.Collection<String> present) {
            return NAMES.stream().filter(name -> !present.contains(name)).toList();
        }

        public int cap(CandidateSource source) {
            return candidateCaps.get(source.name());
        }
    }

    /** Thrown for a config that breaks a rule; lists every broken rule, not only the first. */
    public static final class InvalidRankingConfigException extends RuntimeException {

        private final transient List<String> problems;

        public InvalidRankingConfigException(List<String> problems) {
            super("invalid ranking config: " + String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    private final UUID id;
    private final int version;
    private final int featureSchemaVersion;
    private final double bias;
    private final double[] weights;
    private final Params params;

    private RankingConfig(
            UUID id, int version, int featureSchemaVersion, double bias, double[] weights, Params params) {
        this.id = id;
        this.version = version;
        this.featureSchemaVersion = featureSchemaVersion;
        this.bias = bias;
        this.weights = weights;
        this.params = params;
    }

    /**
     * Builds a config from what is stored ({@code weights} and {@code params} as in
     * {@code feed.ranking_config}), checking it against the schema and the config rules.
     *
     * @throws InvalidRankingConfigException if any rule is broken
     */
    public static RankingConfig of(
            UUID id,
            int version,
            int featureSchemaVersion,
            Map<String, Double> weights,
            Params params,
            FeatureSchema schema) {
        List<String> problems = problems(featureSchemaVersion, weights, params, schema);
        if (!problems.isEmpty()) {
            throw new InvalidRankingConfigException(problems);
        }
        double[] ordered = new double[schema.size()];
        for (int i = 0; i < ordered.length; i++) {
            ordered[i] = weights.get(schema.names().get(i));
        }
        return new RankingConfig(id, version, featureSchemaVersion, weights.get(BIAS), ordered, params);
    }

    /** Every rule the given weights and parameters break; empty when the config is valid. */
    public static List<String> problems(
            int featureSchemaVersion, Map<String, Double> weights, Params params, FeatureSchema schema) {
        List<String> problems = new ArrayList<>();
        if (featureSchemaVersion != schema.version()) {
            problems.add(
                    "featureSchemaVersion is " + featureSchemaVersion + ", the schema is version " + schema.version());
        }
        Set<String> expected = new TreeSet<>(schema.names());
        expected.add(BIAS);
        if (!expected.equals(new TreeSet<>(weights.keySet()))) {
            Set<String> missing = new TreeSet<>(expected);
            missing.removeAll(weights.keySet());
            Set<String> unknown = new TreeSet<>(weights.keySet());
            unknown.removeAll(expected);
            problems.add("weights must name exactly the bias and the schema's features (missing " + missing
                    + ", unknown " + unknown + ")");
        }
        weights.forEach((name, weight) -> {
            if (weight == null || !Double.isFinite(weight) || Math.abs(weight) > 10) {
                problems.add("weight " + name + " must be a finite number between -10 and 10");
            }
        });
        if (!(params.halfLifeHours() > 0 && params.halfLifeHours() <= 720)) {
            problems.add("halfLifeHours must be above 0 and at most 720");
        }
        if (!(params.priorStrength() > 0 && Double.isFinite(params.priorStrength()))) {
            problems.add("priorStrength must be a finite number above 0");
        }
        if (!(params.diversityLambda() >= 0 && params.diversityLambda() <= 1)) {
            problems.add("diversityLambda must be between 0 and 1");
        }
        if (!(params.explorationRate() >= 0 && params.explorationRate() <= 0.3)) {
            problems.add("explorationRate must be between 0 and 0.3");
        }
        if (params.sessionSize() < 1 || params.sessionSize() > 500) {
            problems.add("sessionSize must be between 1 and 500");
        }
        if (params.pageSize() < 1 || params.pageSize() > 50) {
            problems.add("pageSize must be between 1 and 50");
        }
        Set<String> sources = new TreeSet<>();
        Arrays.stream(CandidateSource.values()).forEach(source -> sources.add(source.name()));
        if (!sources.equals(new TreeSet<>(params.candidateCaps().keySet()))) {
            problems.add("candidateCaps must name exactly " + sources);
        }
        params.candidateCaps().forEach((source, cap) -> {
            if (cap == null || cap < 0 || cap > 1000) {
                problems.add("candidateCaps." + source + " must be between 0 and 1000");
            }
        });
        if (!(params.localRadiusKm() > 0 && params.localRadiusKm() <= 1000)) {
            problems.add("localRadiusKm must be above 0 and at most 1000");
        }
        if (params.seenWindowDays() < 0 || params.seenWindowDays() > 30) {
            problems.add("seenWindowDays must be between 0 and 30");
        }
        if (params.authorCapPerSession() < 1 || params.authorCapPerSession() > 50) {
            problems.add("authorCapPerSession must be between 1 and 50");
        }
        return problems;
    }

    public UUID id() {
        return id;
    }

    public int version() {
        return version;
    }

    public int featureSchemaVersion() {
        return featureSchemaVersion;
    }

    public double bias() {
        return bias;
    }

    /** The weight of the feature at this schema index. */
    public double weight(int index) {
        return weights[index];
    }

    public Params params() {
        return params;
    }
}
