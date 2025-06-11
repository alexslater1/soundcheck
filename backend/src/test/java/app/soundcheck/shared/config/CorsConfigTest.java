package app.soundcheck.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * CORS allows the local app origins, and nothing at all when the list is empty (the
 * aws profile), so the local list cannot ride along into production. The end-to-end local behaviour is
 * {@code SmokeIT.corsAllowsOnlyTheLocalAppOrigins}.
 */
class CorsConfigTest {

    @Test
    void theLocalOriginsMayCallTheApiWithCredentials() {
        CorsConfiguration config =
                SecurityConfig.corsFor(SecurityConfig.LOCAL_DEV_ORIGINS).getCorsConfiguration(request("/api/v1/me"));
        assertThat(config).isNotNull();
        assertThat(config.getAllowedOrigins()).containsExactly("http://localhost:8081", "http://localhost:8088");
        assertThat(config.getAllowCredentials()).isTrue();
    }

    @Test
    void noOriginsMeansNoCorsAtAll() {
        for (List<String> none : List.of(List.<String>of(), List.of(""))) {
            CorsConfigurationSource source = SecurityConfig.corsFor(none);
            assertThat(source.getCorsConfiguration(request("/api/v1/me"))).isNull();
        }
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        ServletRequestPathUtils.parseAndCache(request);
        return request;
    }
}
