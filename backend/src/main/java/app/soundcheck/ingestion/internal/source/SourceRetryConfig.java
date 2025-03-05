package app.soundcheck.ingestion.internal.source;

import app.soundcheck.shared.config.ResilientCalls;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code @Retryable(interceptor = "sourceCalls")} on the source and enrichment API clients: a
 * {@link TransientSourceException} is retried up to 4 times, waiting {@code soundcheck.ingestion.retry.delay-ms}
 * (± {@code jitter-ms}), doubled each time up to {@code max-delay-ms}; one call at a time per client method.
 */
@Configuration(proxyBeanMethods = false)
public class SourceRetryConfig {

    @Bean
    public MethodInterceptor sourceCalls(
            @Value("${soundcheck.ingestion.retry.delay-ms:500}") long delay,
            @Value("${soundcheck.ingestion.retry.jitter-ms:250}") long jitter,
            @Value("${soundcheck.ingestion.retry.max-delay-ms:10000}") long maxDelay) {
        return ResilientCalls.interceptor(TransientSourceException.class, 4, delay, jitter, 2, maxDelay, 1);
    }
}
