package app.soundcheck.shared.web;

import java.io.IOException;

/**
 * Thrown while a body without a {@code Content-Length} is being read and passes the limit; the
 * {@link ProblemHandler} finds it under the converter's "not readable" exception and answers 413.
 */
class RequestBodyTooLargeException extends IOException {

    private final long limit;

    RequestBodyTooLargeException(long limit) {
        super("Request body larger than " + limit + " bytes");
        this.limit = limit;
    }

    long limit() {
        return limit;
    }
}
