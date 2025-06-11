package app.soundcheck.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 300 requests a minute per user: every signed-in API request outside the endpoints with
 * their own limits ({@code /auth/**} per IP and email, {@code /events} 120 a minute). It bounds what one
 * account (or one stolen token) can ask of the API, scraping included. An interceptor, like
 * {@code EventRateLimit}: it runs after authentication and before the handler reads a body. Anonymous
 * requests (public paths) are not counted: they would all share one bucket; the edge limits those.
 */
@Configuration(proxyBeanMethods = false)
class UserRateLimit implements WebMvcConfigurer, HandlerInterceptor {

    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;

    UserRateLimit(RateLimiter rateLimiter, RateLimitProperties limits) {
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/auth/**", "/api/v1/events");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication who = SecurityContextHolder.getContext().getAuthentication();
        if (who != null && who.isAuthenticated() && !(who instanceof AnonymousAuthenticationToken)) {
            rateLimiter.enforce("user:" + who.getName(), limits.perUserPerMinute(), Duration.ofMinutes(1));
        }
        return true;
    }
}
