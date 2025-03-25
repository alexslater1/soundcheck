package app.soundcheck.media.internal;

import app.soundcheck.media.internal.worker.WorkerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link MediaProperties} and {@link WorkerProperties}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MediaProperties.class, WorkerProperties.class})
class MediaConfig {}
