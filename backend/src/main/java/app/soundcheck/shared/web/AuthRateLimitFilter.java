package app.soundcheck.shared.web;

import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-IP limit on the auth endpoints (10/min per IP). Runs before Spring Security so an
 * attacker cannot even reach the password check; the per-email limit lives in the identity service
 * because the email is in the JSON body.
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    static final String PATH_PREFIX = "/api/v1/auth/";

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final JsonMapper json;

    public AuthRateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties, JsonMapper json) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX) || !"POST".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            rateLimiter.enforce(
                    "auth:ip:" + request.getRemoteAddr(), properties.auth().perIpPerMinute(), Duration.ofMinutes(1));
        } catch (RateLimitExceededException ex) {
            ProblemDetail problem = ProblemHandler.problem(ex.status(), ex.code(), ex.getMessage());
            response.setStatus(ex.status().value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
            response.setHeader("RateLimit-Limit", String.valueOf(ex.limit()));
            response.setHeader("RateLimit-Remaining", "0");
            response.setHeader("RateLimit-Reset", String.valueOf(ex.retryAfterSeconds()));
            response.getWriter().write(json.writeValueAsString(problem));
            return;
        }
        chain.doFilter(request, response);
    }
}
