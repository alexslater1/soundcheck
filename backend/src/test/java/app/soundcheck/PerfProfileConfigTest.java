package app.soundcheck;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.shared.web.RateLimitProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

/**
 * The {@code perf} profile raises the rate limits and turns on latency histograms; without it
 * nothing changes. Loads the real {@code application*.yaml} files and binds them the way Spring Boot does.
 * The environment holds no variables: {@code make test} exports the developer's {@code .env}, which must not
 * decide these answers.
 */
class PerfProfileConfigTest {

    private static final String HISTOGRAM = "management.metrics.distribution.percentiles-histogram.";

    @Test
    void withoutThePerfProfileTheLimitsAreSpecDefaultsAndThereAreNoHistograms() {
        ConfigurableEnvironment local = load();

        RateLimitProperties limits = rateLimits(local);
        assertThat(limits.auth().perIpPerMinute()).isEqualTo(10);
        assertThat(limits.auth().perEmailPerMinute()).isEqualTo(5);
        assertThat(limits.eventsPerMinute()).isEqualTo(120);
        assertThat(limits.feedBuildsPerMinute()).isEqualTo(30);
        assertThat(limits.perUserPerMinute()).isEqualTo(300);
        assertThat(uploadsPerHour(local)).isEqualTo(30);
        assertThat(local.getProperty(HISTOGRAM + "http.server.requests")).isNull();
        assertThat(local.getProperty("management.opentelemetry.resource-attributes.deployment.environment"))
                .isEqualTo("local");
    }

    @Test
    void thePerfProfileRaisesEveryLimitWithoutSwitchingAnyOff() {
        ConfigurableEnvironment perf = load("perf");

        RateLimitProperties limits = rateLimits(perf);
        assertThat(limits.auth().perIpPerMinute()).isEqualTo(1_000_000);
        assertThat(limits.auth().perEmailPerMinute()).isEqualTo(1_000_000);
        assertThat(limits.eventsPerMinute()).isEqualTo(1_000_000);
        assertThat(limits.feedBuildsPerMinute()).isEqualTo(1_000_000);
        assertThat(limits.perUserPerMinute()).isEqualTo(1_000_000);
        assertThat(uploadsPerHour(perf)).isEqualTo(1_000_000);
    }

    @Test
    void thePerfProfileTagsItsTelemetryAndRecordsLatencyHistograms() {
        ConfigurableEnvironment perf = load("perf");

        assertThat(perf.getProperty(HISTOGRAM + "http.server.requests")).isEqualTo("true");
        assertThat(perf.getProperty(HISTOGRAM + "soundcheck.feed.stage")).isEqualTo("true");
        assertThat(perf.getProperty("management.opentelemetry.resource-attributes.deployment.environment"))
                .isEqualTo("perf");
        assertThat(perf.getProperty("server.port")).isEqualTo("8201");
        assertThat(perf.getProperty("soundcheck.roles")).isEqualTo("api");
    }

    private static RateLimitProperties rateLimits(ConfigurableEnvironment environment) {
        return Binder.get(environment).bindOrCreate("soundcheck.rate-limit", RateLimitProperties.class);
    }

    /** {@code MediaProperties} is internal to the media module, so read its one value by name. */
    private static int uploadsPerHour(ConfigurableEnvironment environment) {
        return Binder.get(environment)
                .bind("soundcheck.media.uploads-per-hour", Integer.class)
                .orElse(30);
    }

    private static ConfigurableEnvironment load(String... profiles) {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new MapPropertySource("no-variables", Map.of()));
        ConfigDataEnvironmentPostProcessor.applyTo(environment, null, null, profiles);
        return environment;
    }
}
