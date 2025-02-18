package app.soundcheck.shared.web;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to RFC 9457 Problem Details: {@code type} is
 * {@code https://soundcheck.app/problems/{slug}}, {@code code} is the stable machine code, and
 * validation failures carry {@code errors: [{field, message}]}. Framework exceptions (404, 405, 415,
 * malformed JSON…) are handled by the Spring base class and given a code here too.
 */
@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {

    static final String PROBLEM_TYPE_BASE = "https://soundcheck.app/problems/";
    static final String CODE_PROPERTY = "code";
    static final String ERRORS_PROPERTY = "errors";

    /** RFC 9110's names for the two statuses Spring 6 still names after RFC 7231 and RFC 4918. */
    private static final Map<Integer, String> RFC_9110_TITLES =
            Map.of(413, "Content Too Large", 422, "Unprocessable Content");

    private static final Map<Integer, String> RFC_9110_CODES =
            Map.of(413, "CONTENT_TOO_LARGE", 422, "UNPROCESSABLE_CONTENT");

    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ProblemDetail> handleRateLimited(RateLimitExceededException ex) {
        ProblemDetail problem = problem(ex.status(), ex.code(), ex.getMessage());
        return ResponseEntity.status(ex.status())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                .header("RateLimit-Limit", String.valueOf(ex.limit()))
                .header("RateLimit-Remaining", "0")
                .header("RateLimit-Reset", String.valueOf(ex.retryAfterSeconds()))
                .body(problem);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        ProblemDetail problem = problem(ex.status(), ex.code(), ex.getMessage());
        return ResponseEntity.status(ex.status()).body(problem);
    }

    /**
     * Security exceptions raised inside controllers/services (e.g. {@code @PreAuthorize}) must not be
     * swallowed by the catch-all below: map them to the same 401/403 problems the filter chain writes.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed"));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
        problem.setProperty(ERRORS_PROPERTY, errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).headers(headers).body(problem);
    }

    /** Gives the framework-produced problems (404, 405, 400 on bad JSON…) a stable code and type. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getProperties() == null) {
            HttpStatus status = HttpStatus.resolve(statusCode.value());
            String code = status == null
                    ? "HTTP_" + statusCode.value()
                    : RFC_9110_CODES.getOrDefault(status.value(), status.name());
            decorate(problem, code);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        decorate(problem, code);
        return problem;
    }

    private static void decorate(ProblemDetail problem, String code) {
        problem.setType(URI.create(PROBLEM_TYPE_BASE + slug(code)));
        String title = RFC_9110_TITLES.get(problem.getStatus());
        if (title != null) {
            problem.setTitle(title);
        }
        problem.setProperty(CODE_PROPERTY, code);
    }

    /** {@code HANDLE_TAKEN} → {@code handle-taken}. */
    static String slug(String code) {
        return code.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
