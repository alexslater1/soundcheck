package app.soundcheck.signals.internal.web;

import app.soundcheck.shared.web.RateLimitProperties;
import app.soundcheck.shared.web.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The rate limit of {@code POST /events} (120 requests a minute per user). An interceptor,
 * so that it runs after authentication but before the body is read and validated: a client over its
 * limit, or one sending bodies that fail validation, costs a token and nothing more.
 */
@Configuration(proxyBeanMethods = false)
class EventRateLimit implements WebMvcConfigurer, HandlerInterceptor {

    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;

    EventRateLimit(RateLimiter rateLimiter, RateLimitProperties limits) {
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/events");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication who = SecurityContextHolder.getContext().getAuthentication();
        if (who != null && who.isAuthenticated()) {
            rateLimiter.enforce("events:" + who.getName(), limits.eventsPerMinute(), Duration.ofMinutes(1));
        }
        return true;
    }
}
