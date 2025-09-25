package app.soundcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

/**
 * What {@code application.yaml} and {@code application-aws.yaml} resolve to, for a laptop and for an ECS
 * task. The environment holds only the variables a test gives it: {@code make test} exports the
 * developer's {@code .env}, which must not decide these answers.
 */
class AwsProfileConfigTest {

    /** The hub's task environment, as far as these properties go. */
    static final Map<String, Object> TASK = Map.of(
            "DB_URL", "jdbc:postgresql://db.example.internal:5432/soundcheck",
            "S3_BUCKET", "soundcheck-demo-media",
            "SQS_QUEUE_URL", "https://sqs.eu-north-1.amazonaws.com/123456789012/soundcheck-transcode",
            "PUBLIC_BASE_URL", "https://d111111abcdef8.cloudfront.net");

    static final String[] EXPORT_SWITCHES = {
        "management.otlp.tracing.export.enabled",
        "management.otlp.logging.export.enabled",
        "management.otlp.metrics.export.enabled"
    };

    static final String[] HEADER_MAPS = {
        "management.otlp.tracing.headers", "management.otlp.logging.headers", "management.otlp.metrics.export.headers"
    };

    @Test
    void locallyTelemetryIsExportedWithNoAuthorizationHeader() {
        ConfigurableEnvironment local = load(Map.of());

        for (String property : EXPORT_SWITCHES) {
            assertThat(local.getProperty(property)).as(property).isEqualTo("true");
        }
        for (String headers : HEADER_MAPS) {
            assertThat(headers(local, headers)).as(headers).isEmpty();
        }
    }

    @Test
    void locallyNothingElseChanges() {
        ConfigurableEnvironment local = load(Map.of());

        assertThat(local.getProperty("soundcheck.queue.url"))
                .isEqualTo("http://localhost:4566/000000000000/soundcheck-transcode");
        assertThat(local.getProperty("soundcheck.storage.bucket")).isEqualTo("soundcheck-media");
        assertThat(local.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://localhost:5432/soundcheck");
        assertThat(local.getProperty("soundcheck.media.public-base-url")).isEqualTo("http://localhost:8088");
        assertThat(local.getProperty("soundcheck.edge.viewer-address-header")).isNull();
        assertThat(local.getProperty("soundcheck.push.credentials-json")).isEmpty();
    }

    @Test
    void onAwsTelemetryIsOffUntilSwitchedOn() {
        ConfigurableEnvironment aws = load(TASK, "aws");

        for (String property : EXPORT_SWITCHES) {
            assertThat(aws.getProperty(property)).as(property).isEqualTo("false");
        }
    }

    @Test
    void onAwsTheSwitchTurnsAllThreeExportsOnWithTheAuthorizationHeader() {
        Map<String, Object> env = new HashMap<>(TASK);
        env.put("OTLP_ENABLED", "true");
        env.put("OTLP_AUTHORIZATION", "Basic c3RhY2s6dG9rZW4=");

        ConfigurableEnvironment aws = load(env, "aws");

        for (String property : EXPORT_SWITCHES) {
            assertThat(aws.getProperty(property)).as(property).isEqualTo("true");
        }
        for (String headers : HEADER_MAPS) {
            assertThat(headers(aws, headers))
                    .as(headers)
                    .containsExactly(Map.entry("Authorization", "Basic c3RhY2s6dG9rZW4="));
        }
    }

    @Test
    void onAwsTheTaskEnvironmentIsUsed() {
        ConfigurableEnvironment aws = load(TASK, "aws");

        assertThat(aws.getProperty("spring.datasource.url")).isEqualTo(TASK.get("DB_URL"));
        assertThat(aws.getProperty("soundcheck.storage.bucket")).isEqualTo(TASK.get("S3_BUCKET"));
        assertThat(aws.getProperty("soundcheck.storage.endpoint")).isEmpty();
        assertThat(aws.getProperty("soundcheck.queue.url")).isEqualTo(TASK.get("SQS_QUEUE_URL"));
        assertThat(aws.getProperty("soundcheck.media.public-base-url")).isEqualTo(TASK.get("PUBLIC_BASE_URL"));
        assertThat(aws.getProperty("soundcheck.edge.viewer-address-header")).isEqualTo("CloudFront-Viewer-Address");
    }

    /** A task missing one of these stops at startup and names it, instead of reaching for localhost. */
    @ParameterizedTest
    @ValueSource(strings = {"DB_URL", "S3_BUCKET", "SQS_QUEUE_URL", "PUBLIC_BASE_URL"})
    void onAwsAMissingSettingFailsInsteadOfFallingBackToLocalhost(String variable) {
        Map<String, Object> env = new HashMap<>(TASK);
        env.remove(variable);
        ConfigurableEnvironment aws = load(env, "aws");

        String property =
                switch (variable) {
                    case "DB_URL" -> "spring.datasource.url";
                    case "S3_BUCKET" -> "soundcheck.storage.bucket";
                    case "SQS_QUEUE_URL" -> "soundcheck.queue.url";
                    default -> "soundcheck.media.public-base-url";
                };
        assertThatThrownBy(() -> aws.getProperty(property))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Could not resolve placeholder '" + variable + "'");
    }

    @Test
    void thePushKeyCanArriveAsAnEnvironmentVariable() {
        Map<String, Object> env = new HashMap<>(TASK);
        env.put("PUSH_CREDENTIALS_JSON", "{\"project_id\": \"p\"}");

        assertThat(load(env, "aws").getProperty("soundcheck.push.credentials-json"))
                .isEqualTo("{\"project_id\": \"p\"}");
    }

    private static Map<String, String> headers(ConfigurableEnvironment environment, String name) {
        return Binder.get(environment)
                .bind(name, Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
    }

    /** application.yaml (+ application-{profile}.yaml) over exactly {@code variables}, as Boot loads them. */
    private static ConfigurableEnvironment load(Map<String, Object> variables, String... profiles) {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new MapPropertySource("task-environment", Map.copyOf(variables)));
        ConfigDataEnvironmentPostProcessor.applyTo(environment, null, null, profiles);
        return environment;
    }
}
