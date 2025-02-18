package app.soundcheck.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The nested record must bind to its defaults even when no property is configured. */
class RateLimitPropertiesTest {

    @Test
    void bindsDefaultsWhenNothingIsConfigured() {
        RateLimitProperties props = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("soundcheck.rate-limit", RateLimitProperties.class);

        assertThat(props.auth()).isNotNull();
        assertThat(props.auth().perIpPerMinute()).isEqualTo(10);
        assertThat(props.auth().perEmailPerMinute()).isEqualTo(5);
    }

    @Test
    void bindsConfiguredValues() {
        RateLimitProperties props = new Binder(
                        new MapConfigurationPropertySource(Map.of("soundcheck.rate-limit.auth.per-ip-per-minute", "6")))
                .bindOrCreate("soundcheck.rate-limit", RateLimitProperties.class);

        assertThat(props.auth().perIpPerMinute()).isEqualTo(6);
        assertThat(props.auth().perEmailPerMinute()).isEqualTo(5);
    }
}
