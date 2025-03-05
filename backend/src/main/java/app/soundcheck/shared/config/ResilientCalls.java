package app.soundcheck.shared.config;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.BackOffContext;
import org.springframework.retry.backoff.BackOffInterruptedException;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.interceptor.RetryInterceptorBuilder;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.util.ConcurrencyThrottleSupport;

/**
 * Retries around a concurrency limit, for {@code @Retryable(interceptor = …)} on an external API client.
 *
 * <p>The limit is outermost and counted per bean and method: a call holds its permit through every attempt and
 * back-off, and callers over the limit wait. An exception of the retried type, or caused by one, is tried
 * again up to {@code maxRetries} more times; when they run out, or on any other exception, the caller gets the
 * last exception itself. Back-off is exponential from {@code delay} (times {@code multiplier}, at most
 * {@code maxDelay}), each wait drawn at random within {@code jitter} (scaled like the delay) of it but never
 * under {@code delay}. An interrupted back-off also ends with the last exception, the interrupt flag set again.
 */
public final class ResilientCalls {

    private ResilientCalls() {}

    public static MethodInterceptor interceptor(
            Class<? extends Throwable> retryOn,
            int maxRetries,
            long delay,
            long jitter,
            double multiplier,
            long maxDelay,
            int concurrencyLimit) {
        RetryOperationsInterceptor retry = RetryInterceptorBuilder.stateless()
                .retryPolicy(new SimpleRetryPolicy(maxRetries + 1, Map.of(retryOn, true), true))
                .backOffPolicy(new JitteredBackOff(delay, jitter, multiplier, maxDelay))
                .build();
        Map<Target, Throttle> throttles = new ConcurrentHashMap<>();
        return invocation -> throttles
                .computeIfAbsent(
                        new Target(invocation.getThis(), invocation.getMethod()),
                        target -> new Throttle(concurrencyLimit))
                .call(invocation, retry);
    }

    private record Target(Object bean, Method method) {}

    private static final class Throttle extends ConcurrencyThrottleSupport {

        Throttle(int limit) {
            setConcurrencyLimit(limit);
        }

        Object call(MethodInvocation invocation, MethodInterceptor inner) throws Throwable {
            beforeAccess();
            try {
                return inner.invoke(invocation);
            } finally {
                afterAccess();
            }
        }
    }

    private record JitteredBackOff(long delay, long jitter, double multiplier, long maxDelay) implements BackOffPolicy {

        @Override
        public BackOffContext start(RetryContext context) {
            return new Waits(context);
        }

        @Override
        public void backOff(BackOffContext backOffContext) {
            Waits waits = (Waits) backOffContext;
            waits.interval = waits.interval < 0
                    ? delay
                    : waits.interval >= maxDelay ? maxDelay : Math.min((long) (waits.interval * multiplier), maxDelay);
            try {
                Thread.sleep(Math.min(withJitter(waits.interval), maxDelay));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (waits.retry.getLastThrowable() instanceof RuntimeException last) {
                    throw last;
                }
                throw new BackOffInterruptedException("Interrupted while waiting to retry", e);
            }
        }

        private long withJitter(long interval) {
            if (jitter <= 0) {
                return interval;
            }
            long scaled = jitter * (delay > 0 ? interval / delay : 1);
            long low = Math.max(interval - scaled, delay);
            long high = Math.min(interval + scaled, maxDelay);
            return low + (long) (Math.random() * (high - low));
        }
    }

    private static final class Waits implements BackOffContext {

        final RetryContext retry;
        long interval = -1;

        Waits(RetryContext retry) {
            this.retry = retry;
        }
    }
}
