package app.soundcheck.shared.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code soundcheck.rate-limit.*} — the defaults; tests raise them. */
@ConfigurationProperties(prefix = "soundcheck.rate-limit")
public record RateLimitProperties(Auth auth) {

    public record Auth(@DefaultValue("10") long perIpPerMinute, @DefaultValue("5") long perEmailPerMinute) {}
}
