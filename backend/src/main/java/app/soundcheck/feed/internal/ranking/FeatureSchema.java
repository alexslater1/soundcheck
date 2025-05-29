package app.soundcheck.feed.internal.ranking;

import app.soundcheck.shared.config.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * The ordered list of ranking features. The order is the index into every feature vector,
 * every weight array and {@code feed.served_item.features}; it is defined once, in
 * {@code contracts/ranking/feature-schema.json}, which the Python lab reads too.
 */
public record FeatureSchema(int version, List<String> names) {

    static final String RESOURCE = "/ranking/feature-schema.json";

    public FeatureSchema {
        names = List.copyOf(names);
        if (names.isEmpty() || new HashSet<>(names).size() != names.size()) {
            throw new IllegalArgumentException("feature names must be unique and not empty");
        }
    }

    /** The schema packaged with the application. */
    public static FeatureSchema load() {
        try (InputStream in = FeatureSchema.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the classpath");
            }
            return parse(JsonMappers.builder().build().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads the file's JSON; the indexes must be 0, 1, 2, … in the order listed. */
    static FeatureSchema parse(JsonNode json) {
        List<String> names = new ArrayList<>();
        for (JsonNode feature : json.path("features")) {
            if (feature.path("index").asInt(-1) != names.size()) {
                throw new IllegalArgumentException("feature indexes must count up from 0 in order; found "
                        + feature.path("index") + " at " + names.size());
            }
            names.add(feature.path("name").asText(""));
        }
        return new FeatureSchema(json.path("version").asInt(), names);
    }

    public int size() {
        return names.size();
    }

    /** The position of a feature; an unknown name is a programming error. */
    public int indexOf(String name) {
        int index = names.indexOf(name);
        if (index < 0) {
            throw new IllegalArgumentException("no feature named " + name);
        }
        return index;
    }
}
