package app.soundcheck.shared.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * An error that maps to an RFC 9457 Problem Details response. {@code code} is the stable
 * machine code (e.g. {@code HANDLE_TAKEN}); {@link ProblemHandler} derives {@code type} from it.
 * {@link #with} adds a property the client can act on (the post that replaced a hidden one).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String code, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, detail);
    }

    public static ApiException unauthorized(String code, String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, detail);
    }

    public static ApiException forbidden(String code, String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, code, detail);
    }

    public static ApiException notFound(String code, String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, code, detail);
    }

    public static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, detail);
    }

    /** 422: the request is well formed but breaks a rule of the domain (a Went on a gig still to come). */
    public static ApiException unprocessable(String code, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, detail);
    }

    public static ApiException gone(String code, String detail) {
        return new ApiException(HttpStatus.GONE, code, detail);
    }

    /** Adds a property to the problem the client receives; never a token, a password or a presigned URL. */
    public ApiException with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public Map<String, Object> properties() {
        return Collections.unmodifiableMap(properties);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
