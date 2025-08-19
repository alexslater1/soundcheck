package app.soundcheck;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every console route is closed to anyone who isn't an operator. The routes are read from Spring
 * MVC's own mapping table, so a route added later is swept without touching this test: no token → 401,
 * a USER's token → 403, the admin's token → neither. The actuator's internals get the same check.
 */
class AdminAccessIT extends IntegrationTest {

    static final String ADMIN_PREFIX = "/api/v1/admin";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
    }

    record Route(HttpMethod method, String path) {}

    @Test
    void everyAdminRouteIsForOperatorsOnly() {
        List<Route> routes = adminRoutes();
        assertThat(routes)
                .as("admin routes found (an empty sweep would prove nothing)")
                .isNotEmpty();
        TestApi.User user = api.register("notadmin");
        TestApi.User admin = api.admin();

        for (Route route : routes) {
            Object body = hasBody(route.method()) ? Map.of() : null;
            assertThat(status(api.call(route.method(), route.path(), body, null).getStatusCode()))
                    .as("%s %s without a token", route.method(), route.path())
                    .isEqualTo(401);
            assertThat(status(api.call(route.method(), route.path(), body, user).getStatusCode()))
                    .as("%s %s as a USER", route.method(), route.path())
                    .isEqualTo(403);
            assertThat(status(
                            api.call(route.method(), route.path(), body, admin).getStatusCode()))
                    .as("%s %s as the admin", route.method(), route.path())
                    .isNotIn(401, 403);
        }
    }

    @Test
    void theActuatorsInternalsAreForOperatorsOnlyButHealthStaysPublic() {
        TestApi.User user = api.register("peek");
        for (String path : List.of("/actuator/metrics", "/actuator/info", "/actuator/modulith")) {
            assertThat(status(api.get(path, null).getStatusCode())).as(path).isEqualTo(401);
            assertThat(status(api.get(path, user).getStatusCode())).as(path).isEqualTo(403);
            assertThat(status(api.get(path, api.admin()).getStatusCode()))
                    .as(path)
                    .isNotIn(401, 403);
        }
        assertThat(api.get("/actuator/health", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.get("/actuator/health/liveness", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Each (method, pattern) under /api/v1/admin, with every path variable filled with a random UUID. */
    private List<Route> adminRoutes() {
        List<Route> routes = new ArrayList<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith(ADMIN_PREFIX)) {
                    continue;
                }
                String path = pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
                var methods = info.getMethodsCondition().getMethods();
                if (methods.isEmpty()) {
                    routes.add(new Route(HttpMethod.GET, path));
                }
                for (RequestMethod method : methods) {
                    routes.add(new Route(method.asHttpMethod(), path));
                }
            }
        }
        return routes;
    }

    private static boolean hasBody(HttpMethod method) {
        return method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH;
    }

    private static int status(HttpStatusCode code) {
        return code.value();
    }
}
