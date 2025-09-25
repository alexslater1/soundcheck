package app.soundcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.unit.DataSize;

/**
 * No configuration record prints a secret. Every
 * {@code @ConfigurationProperties} class under {@code app.soundcheck} is built with a marker value in
 * each secret-named component (nested records included) and its {@code toString} must not contain one.
 * New records are covered without touching this test.
 */
class ConfigurationNeverPrintsSecretsTest {

    private static final Pattern SECRET_NAME =
            Pattern.compile("(?i).*(password|secret|apikey|token|pem|accesskey|credentialsjson).*");
    private static final String MARKER = "SECRET-VALUE-";

    private final Set<String> secretComponents = new TreeSet<>();

    @Test
    void noConfigurationRecordPrintsASecret() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));
        List<String> leaks = new ArrayList<>();
        int roots = 0;
        for (var candidate : scanner.findCandidateComponents("app.soundcheck")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            if (!type.isRecord()) {
                continue;
            }
            roots++;
            String printed = String.valueOf(build(type));
            if (printed.contains(MARKER)) {
                leaks.add(type.getName() + " → " + printed);
            }
        }
        assertThat(roots).as("configuration records found").isGreaterThan(5);
        assertThat(secretComponents)
                .as("the secrets the configuration holds today were all exercised")
                .contains(
                        "AdminProperties.password",
                        "Jwt.privateKeyPem",
                        "Lastfm.apiKey",
                        "PushProperties.credentialsJson",
                        "Setlistfm.apiKey",
                        "Ticketmaster.apiKey",
                        "StorageProperties.secretKey");
        assertThat(leaks)
                .as("configuration records whose toString prints a secret")
                .isEmpty();
    }

    private Object build(Class<?> record) throws Exception {
        RecordComponent[] components = record.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            args[i] = sample(record, components[i]);
        }
        Constructor<?> constructor = record.getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    private Object sample(Class<?> owner, RecordComponent component) throws Exception {
        Class<?> type = component.getType();
        if (type == String.class) {
            if (SECRET_NAME.matcher(component.getName()).matches()) {
                secretComponents.add(owner.getSimpleName() + "." + component.getName());
                return MARKER + owner.getSimpleName() + "." + component.getName();
            }
            return "value";
        }
        if (type.isRecord()) {
            return build(type);
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 1;
        }
        if (type == long.class) {
            return 1L;
        }
        if (type == double.class) {
            return 1.0;
        }
        if (type == Duration.class) {
            return Duration.ofSeconds(1);
        }
        if (type == DataSize.class) {
            return DataSize.ofMegabytes(1);
        }
        if (type == List.class) {
            return List.of();
        }
        if (type == Set.class) {
            return Set.of();
        }
        if (type == Map.class) {
            return Map.of();
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        return null;
    }
}
