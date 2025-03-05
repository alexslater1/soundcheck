package app.soundcheck.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Turns on spring-retry's {@code @Retryable}, whose interceptors ({@link ResilientCalls}) retry the external API
 * clients' calls within a concurrency limit. Class-based proxies: the Last.fm and MusicBrainz clients carry the
 * annotation on the implementing class, not on their interface.
 */
@Configuration(proxyBeanMethods = false)
@EnableRetry(proxyTargetClass = true)
class ResilienceConfig {}
