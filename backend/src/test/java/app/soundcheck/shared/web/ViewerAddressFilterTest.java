package app.soundcheck.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.shared.config.JsonMappers;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The viewer's address from CloudFront-Viewer-Address, and the per-IP auth limit keyed on it. */
class ViewerAddressFilterTest {

    static final String HEADER = "CloudFront-Viewer-Address";
    /** What Tomcat reports behind CloudFront → ALB: the edge, not the viewer. */
    static final String EDGE = "130.176.0.10";

    final ViewerAddressFilter filter = new ViewerAddressFilter(HEADER);

    @Test
    void ipv4() throws Exception {
        assertThat(remoteAddrSeenBehind("198.51.100.10:46532")).isEqualTo("198.51.100.10");
    }

    @Test
    void ipv6InBrackets() throws Exception {
        assertThat(remoteAddrSeenBehind("[2001:db8::1]:46532")).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    /** CloudFront's own documentation shows IPv6 without brackets: the last colon separates the port. */
    @Test
    void ipv6WithoutBrackets() throws Exception {
        assertThat(remoteAddrSeenBehind("2001:db8:85a3::8a2e:370:7334:46532"))
                .isEqualTo("2001:db8:85a3:0:0:8a2e:370:7334");
    }

    @Test
    void noHeaderLeavesTheRequestAlone() throws Exception {
        assertThat(remoteAddrSeenBehind(null)).isEqualTo(EDGE);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "   ",
                "garbage",
                "not-an-ip:443",
                "example.com:443",
                "999.1.1.1:80",
                "12345:80",
                "[2001:db8::1:46532",
                "[nonsense]:80",
                "198.51.100.10:46532, 203.0.113.9:1"
            })
    void anUnparseableHeaderLeavesTheRequestAlone(String header) throws Exception {
        assertThat(remoteAddrSeenBehind(header)).isEqualTo(EDGE);
    }

    /** Two viewers behind one edge get a bucket each; without the filter they would share the edge's. */
    @Test
    void theAuthRateLimitKeysOnTheViewer() throws Exception {
        AuthRateLimitFilter limit = new AuthRateLimitFilter(
                new RateLimiter(),
                new RateLimitProperties(new RateLimitProperties.Auth(1, 5), 120, 30, 300),
                JsonMappers.builder().build());

        assertThat(login("198.51.100.10:1111", limit)).isEqualTo(200);
        assertThat(login("203.0.113.7:2222", limit)).isEqualTo(200);
        assertThat(login("198.51.100.10:3333", limit)).isEqualTo(429);
    }

    @Test
    void runsBeforeTheAuthRateLimit() {
        int viewer = ViewerAddressFilter.class.getAnnotation(Order.class).value();
        int auth = AuthRateLimitFilter.class.getAnnotation(Order.class).value();

        assertThat(viewer).isLessThan(auth);
    }

    /** Only application-aws.yaml names the header; locally a client could send it, so no filter at all. */
    @Test
    void registeredOnlyWhenTheHeaderIsConfigured() {
        ApplicationContextRunner runner =
                new ApplicationContextRunner().withUserConfiguration(ViewerAddressFilter.class);

        runner.run(context -> assertThat(context).doesNotHaveBean(ViewerAddressFilter.class));
        runner.withPropertyValues("soundcheck.edge.viewer-address-header=" + HEADER)
                .run(context -> assertThat(context).hasSingleBean(ViewerAddressFilter.class));
    }

    private String remoteAddrSeenBehind(String header) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/gigs");
        request.setRemoteAddr(EDGE);
        if (header != null) {
            request.addHeader(HEADER, header);
        }
        List<String> seen = new ArrayList<>();
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain(new Endpoint(seen)));
        return seen.getFirst();
    }

    private int login(String viewer, AuthRateLimitFilter limit) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(EDGE);
        request.addHeader(HEADER, viewer);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new MockFilterChain(new Endpoint(new ArrayList<>()), filter, limit).doFilter(request, response);
        return response.getStatus();
    }

    /** Stands in for the controller: answers 200 and notes the address it was given. */
    private static final class Endpoint extends HttpServlet {

        private final transient List<String> seen;

        Endpoint(List<String> seen) {
            this.seen = seen;
        }

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) {
            seen.add(request.getRemoteAddr());
        }
    }
}
