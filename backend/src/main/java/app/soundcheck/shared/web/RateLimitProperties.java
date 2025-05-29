package app.soundcheck.shared.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code soundcheck.rate-limit.*} — the defaults; tests raise them. The nested record carries
 * {@code @DefaultValue} so it is instantiated with its defaults even when no property is set at all
 * (without it {@code auth()} is null — found by the live smoke test).
 */
@ConfigurationProperties(prefix = "soundcheck.rate-limit")
public record RateLimitProperties(
        @DefaultValue Auth auth,
        @DefaultValue("120") long eventsPerMinute,
        @DefaultValue("30") long feedBuildsPerMinute) {

    public record Auth(@DefaultValue("10") long perIpPerMinute, @DefaultValue("5") long perEmailPerMinute) {}
}
