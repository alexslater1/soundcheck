package app.soundcheck.shared.web;

import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses API request bodies over {@code soundcheck.http.max-request-body} with {@code 413
 * REQUEST_TOO_LARGE}. A declared {@code Content-Length} over the
 * limit is refused before anything is read; a body without one (chunked) is counted as it is read and
 * stops at the limit, so the server never buffers more than the limit for one request. Runs after the
 * observation filter, so the problem carries the trace id, and before the Spring Security chain.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@EnableConfigurationProperties(HttpLimitsProperties.class)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    static final String CODE = "REQUEST_TOO_LARGE";

    private final long maxBytes;
    private final JsonMapper json;

    public RequestBodyLimitFilter(HttpLimitsProperties properties, JsonMapper json) {
        this.maxBytes = properties.maxRequestBody().toBytes();
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            ProblemDetail problem = tooLarge(maxBytes);
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(json.writeValueAsString(problem));
            return;
        }
        chain.doFilter(declared >= 0 ? request : new CountingRequest(request, maxBytes), response);
    }

    static ProblemDetail tooLarge(long maxBytes) {
        return ProblemHandler.problem(
                HttpStatus.PAYLOAD_TOO_LARGE, CODE, "The request body is larger than " + maxBytes + " bytes");
    }

    /** A request whose body may be read up to {@code limit} bytes, then fails. */
    static final class CountingRequest extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;

        CountingRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CountingStream(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class CountingStream extends ServletInputStream {

        private final ServletInputStream in;
        private final long limit;
        private long read;

        CountingStream(ServletInputStream in, long limit) {
            this.in = in;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            if (read > limit) {
                throw new RequestBodyTooLargeException(limit);
            }
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
