package app.soundcheck.feed.internal.ranking;

import java.util.Arrays;

/**
 * The features of one post for one viewer, in schema order, each in [0, 1]. Built by name,
 * read by index.
 */
public final class FeatureVector {

    private final double[] values;

    private FeatureVector(double[] values) {
        this.values = values;
    }

    public static Builder builder(FeatureSchema schema) {
        return new Builder(schema);
    }

    /** A vector from values already in schema order (the parity cases, a stored session). */
    public static FeatureVector of(double[] values) {
        return new FeatureVector(values.clone());
    }

    public double get(int index) {
        return values[index];
    }

    public int size() {
        return values.length;
    }

    public double[] toArray() {
        return values.clone();
    }

    /** As stored in {@code feed.served_item.features} ({@code real[]}). */
    public Float[] toFloats() {
        Float[] floats = new Float[values.length];
        for (int i = 0; i < values.length; i++) {
            floats[i] = (float) values[i];
        }
        return floats;
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }

    /** Features not set stay 0. */
    public static final class Builder {

        private final FeatureSchema schema;
        private final double[] values;

        private Builder(FeatureSchema schema) {
            this.schema = schema;
            this.values = new double[schema.size()];
        }

        /** Sets a feature; the value is cut to [0, 1], and "not a number" is refused. */
        public Builder set(String name, double value) {
            if (Double.isNaN(value)) {
                throw new IllegalArgumentException("feature " + name + " is not a number");
            }
            values[schema.indexOf(name)] = Math.max(0, Math.min(1, value));
            return this;
        }

        public FeatureVector build() {
            return new FeatureVector(values.clone());
        }
    }
}
