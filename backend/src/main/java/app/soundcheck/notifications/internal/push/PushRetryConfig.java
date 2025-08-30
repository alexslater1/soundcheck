package app.soundcheck.notifications.internal.push;

import app.soundcheck.shared.config.ResilientCalls;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code @Retryable(interceptor = "pushCalls")} on {@link FcmClient#send}: a {@link TransientPushException} is
 * retried up to 3 times after 500 ms, 1 s and 2 s; at most 4 sends at once.
 */
@Configuration(proxyBeanMethods = false)
class PushRetryConfig {

    @Bean
    MethodInterceptor pushCalls() {
        return ResilientCalls.interceptor(TransientPushException.class, 3, 500, 0, 2, Long.MAX_VALUE, 4);
    }
}
