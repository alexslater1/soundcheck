package app.soundcheck.shared.web;

import java.time.Duration;
import org.springframework.http.HttpStatus;

/** {@code 429 RATE_LIMITED}; {@link ProblemHandler} adds {@code Retry-After} and {@code RateLimit-*} headers. */
public class RateLimitExceededException extends ApiException {

    private final Duration retryAfter;
    private final long limit;

    public RateLimitExceededException(Duration retryAfter, long limit) {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests; try again later");
        this.retryAfter = retryAfter;
        this.limit = limit;
    }

    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }

    public long limit() {
        return limit;
    }
}
