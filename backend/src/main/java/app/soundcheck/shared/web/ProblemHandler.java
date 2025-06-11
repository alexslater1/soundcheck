package app.soundcheck.shared.web;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.web.method.annotation.HandlerMethodValidationException;
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
    static final String TRACE_ID_PROPERTY = "traceId";

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

    /**
     * Constraint annotations on request parameters ({@code @Max(200) radiusKm}) in a {@code @Validated}
     * controller fail with this exception rather than {@code MethodArgumentNotValidException}; it gets
     * the same 400 shape. The field is the parameter name (the last node of the violation's path).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(violation -> {
                    String path = violation.getPropertyPath().toString();
                    return Map.of(
                            "field", path.substring(path.lastIndexOf('.') + 1), "message", violation.getMessage());
                })
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
        problem.setProperty(ERRORS_PROPERTY, errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        if (textTheDatabaseCannotStore(ex)) {
            ProblemDetail problem = problem(
                    HttpStatus.BAD_REQUEST, "INVALID_TEXT", "The text contains a character that cannot be stored");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
        }
        log.error("Unhandled exception", ex);
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    /**
     * PostgreSQL text cannot hold the NUL character (U+0000), which JSON can carry: the driver or the
     * server refuses the statement. That is the client's mistake, wherever the text was headed.
     */
    private static boolean textTheDatabaseCannotStore(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String message = String.valueOf(sql.getMessage());
                return "22021".equals(sql.getSQLState()) || message.contains("Zero bytes may not occur");
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
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

    /** The same 400 shape when Spring MVC's own method validation (no {@code @Validated} proxy) rejects a parameter. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> Map.of(
                                "field",
                                String.valueOf(result.getMethodParameter().getParameterName()),
                                "message",
                                String.valueOf(error.getDefaultMessage()))))
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
        traceId().ifPresent(id -> problem.setProperty(TRACE_ID_PROPERTY, id));
    }

    /**
     * The current trace's id (a correlation traceId is returned instead of internals): a user can quote
     * it and the request is one search away in Tempo, while the response itself says nothing internal.
     * Read from OpenTelemetry's context, where Micrometer Tracing keeps the current span, because the
     * filter chain (401, 403, auth 429) builds its problems statically, outside any Spring bean. Empty
     * when nothing is traced (tracing off, or a test without a span).
     */
    static Optional<String> traceId() {
        SpanContext context = Span.current().getSpanContext();
        return context.isValid() ? Optional.of(context.getTraceId()) : Optional.empty();
    }

    /** {@code HANDLE_TAKEN} → {@code handle-taken}. */
    static String slug(String code) {
        return code.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
