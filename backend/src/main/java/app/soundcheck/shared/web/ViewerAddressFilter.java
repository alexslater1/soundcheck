package app.soundcheck.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * On AWS the request path is viewer → CloudFront → internal ALB → task. Tomcat's {@code RemoteIpValve}
 * reads {@code X-Forwarded-For} right to left and stops at the first address that isn't an internal proxy:
 * that is the CloudFront edge, so every viewer behind one edge would share a per-IP limit. CloudFront puts
 * the viewer's own address in {@code CloudFront-Viewer-Address} ({@code ip:port}), and sets it itself, so
 * this filter makes that the request's remote address before anything reads it.
 *
 * <p>Only registered when {@code soundcheck.edge.viewer-address-header} is set, which only
 * {@code application-aws.yaml} does: locally there is no CloudFront, and a client could send the header.
 * A missing or unparseable header leaves the request as it was.
 */
@Component
@ConditionalOnProperty(name = "soundcheck.edge.viewer-address-header")
@Order(Ordered.HIGHEST_PRECEDENCE) // before AuthRateLimitFilter, the first reader of the address
public class ViewerAddressFilter extends OncePerRequestFilter {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");

    private final String header;

    public ViewerAddressFilter(@Value("${soundcheck.edge.viewer-address-header}") String header) {
        this.header = header;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> viewer = viewerAddress(request.getHeader(header));
        chain.doFilter(
                viewer.<HttpServletRequest>map(ip -> new ViewerRequest(request, ip))
                        .orElse(request),
                response);
    }

    /**
     * The IP in {@code 198.51.100.10:46532}, {@code [2001:db8::1]:46532} or {@code 2001:db8::1:46532}
     * (IPv6 without brackets: the last colon separates the port), normalised; empty for anything else.
     * Parsed as a literal only, never looked up in DNS.
     */
    static Optional<String> viewerAddress(@Nullable String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            return Optional.empty();
        }
        String text = value.strip();
        String host;
        if (text.startsWith("[")) {
            int end = text.indexOf(']');
            if (end < 0) {
                return Optional.empty();
            }
            host = text.substring(1, end);
        } else {
            int colon = text.lastIndexOf(':');
            host = colon < 0 ? text : text.substring(0, colon);
        }
        // InetAddress.getByName also takes legacy forms ("12345" is 0.0.48.57): only dotted quads and IPv6.
        if (!IPV4.matcher(host).matches() && !IPV6.matcher(host).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(literal(host).getHostAddress());
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    /**
     * A dotted quad or IPv6 text as an address, without DNS: a dotted quad is read here (parts up to 255,
     * leading zeros decimal), IPv6 goes to {@code getByName} in brackets, which never looks it up.
     */
    private static InetAddress literal(String host) throws UnknownHostException {
        if (IPV4.matcher(host).matches()) {
            String[] parts = host.split("\\.");
            byte[] bytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                int part = Integer.parseInt(parts[i]);
                if (part > 255) {
                    throw new UnknownHostException(host);
                }
                bytes[i] = (byte) part;
            }
            return InetAddress.getByAddress(bytes);
        }
        return InetAddress.getByName("[" + host + "]");
    }

    /** The request as the viewer sent it: its remote address is the viewer's, not the edge's. */
    private static final class ViewerRequest extends HttpServletRequestWrapper {

        private final String address;

        ViewerRequest(HttpServletRequest request, String address) {
            super(request);
            this.address = address;
        }

        @Override
        public String getRemoteAddr() {
            return address;
        }

        @Override
        public String getRemoteHost() {
            return address;
        }
    }
}
