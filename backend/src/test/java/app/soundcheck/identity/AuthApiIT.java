package app.soundcheck.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Register → login → refresh twice → reuse the first refresh token → whole family
 * revoked (401 REFRESH_REUSED); web cookie attributes; duplicate email/handle → 409 with codes;
 * validation → 400; wrong password → 401; access tokens open protected routes.
 */
class AuthApiIT extends IntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void androidRegisterLoginRefreshRotationAndReuseDetection() {
        String email = "fan-" + UUID.randomUUID() + "@example.com";
        String handle = "fan_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");

        ResponseEntity<Map<String, Object>> registered = post(
                "/api/v1/auth/register",
                Map.of("email", email, "password", "correct horse battery", "handle", handle, "displayName", "Fan"),
                "android",
                null);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = registered.getBody();
        assertThat(body).containsKeys("accessToken", "expiresIn", "refreshToken", "user");
        assertThat(((Map<?, ?>) body.get("user")).get("handle")).isEqualTo(handle);
        assertThat(registered.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();

        // The access token opens a protected route (403 = authenticated but no such endpoint exists yet → 404
        // is what Spring gives for an unknown path once past security).
        HttpHeaders bearer = new HttpHeaders();
        bearer.setBearerAuth((String) body.get("accessToken"));
        ResponseEntity<String> protectedRoute =
                rest.exchange("/api/v1/does-not-exist", HttpMethod.GET, new HttpEntity<>(bearer), String.class);
        assertThat(protectedRoute.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map<String, Object>> login = post(
                "/api/v1/auth/login", Map.of("email", email, "password", "correct horse battery"), "android", null);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String rt1 = (String) login.getBody().get("refreshToken");

        ResponseEntity<Map<String, Object>> refresh1 =
                post("/api/v1/auth/refresh", Map.of("refreshToken", rt1), "android", null);
        assertThat(refresh1.getStatusCode()).isEqualTo(HttpStatus.OK);
        String rt2 = (String) refresh1.getBody().get("refreshToken");
        assertThat(rt2).isNotEqualTo(rt1);

        ResponseEntity<Map<String, Object>> refresh2 =
                post("/api/v1/auth/refresh", Map.of("refreshToken", rt2), "android", null);
        assertThat(refresh2.getStatusCode()).isEqualTo(HttpStatus.OK);
        String rt3 = (String) refresh2.getBody().get("refreshToken");

        // Reuse of the first (already rotated) token → the whole family is revoked.
        ResponseEntity<Map<String, Object>> reuse =
                post("/api/v1/auth/refresh", Map.of("refreshToken", rt1), "android", null);
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reuse.getBody().get("code")).isEqualTo("REFRESH_REUSED");

        ResponseEntity<Map<String, Object>> afterRevocation =
                post("/api/v1/auth/refresh", Map.of("refreshToken", rt3), "android", null);
        assertThat(afterRevocation.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(afterRevocation.getBody().get("code")).isEqualTo("REFRESH_REUSED");

        ResponseEntity<Map<String, Object>> wrongPassword =
                post("/api/v1/auth/login", Map.of("email", email, "password", "not the password"), "android", null);
        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody().get("code")).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void webUsesAStrictHttpOnlyCookieAndTheRequestedWithHeader() {
        String email = "web-" + UUID.randomUUID() + "@example.com";
        String handle = "web_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");

        ResponseEntity<Map<String, Object>> registered = post(
                "/api/v1/auth/register",
                Map.of("email", email, "password", "correct horse battery", "handle", handle, "displayName", "Web"),
                "web",
                null);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registered.getBody().get("refreshToken")).isNull();
        String setCookie = registered.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .startsWith("soundcheck_rt=")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                .contains("Path=/api/v1/auth")
                .contains("Max-Age=2592000");
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));

        // Missing X-Requested-With → 403 even with the cookie.
        HttpHeaders noHeader = new HttpHeaders();
        noHeader.add(HttpHeaders.COOKIE, cookie);
        ResponseEntity<Map<String, Object>> csrf = post("/api/v1/auth/refresh", null, "web", noHeader);
        assertThat(csrf.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(csrf.getBody().get("code")).isEqualTo("CSRF_HEADER_MISSING");

        HttpHeaders withHeader = new HttpHeaders();
        withHeader.add(HttpHeaders.COOKIE, cookie);
        withHeader.add("X-Requested-With", "soundcheck");
        ResponseEntity<Map<String, Object>> refreshed = post("/api/v1/auth/refresh", null, "web", withHeader);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .startsWith("soundcheck_rt=")
                .isNotEqualTo(setCookie);

        ResponseEntity<Map<String, Object>> loggedOut = post("/api/v1/auth/logout", null, "web", withHeader);
        assertThat(loggedOut.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(loggedOut.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    }

    @Test
    void duplicatesValidationAndReservedHandles() {
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        String handle = "dup_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        Map<String, String> ok =
                Map.of("email", email, "password", "correct horse battery", "handle", handle, "displayName", "Dup");
        assertThat(post("/api/v1/auth/register", ok, "android", null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map<String, Object>> sameEmail = post(
                "/api/v1/auth/register",
                Map.of(
                        "email",
                        email.toUpperCase(),
                        "password",
                        "correct horse battery",
                        "handle",
                        handle + "x",
                        "displayName",
                        "Dup"),
                "android",
                null);
        assertThat(sameEmail.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sameEmail.getBody().get("code")).isEqualTo("EMAIL_TAKEN");

        ResponseEntity<Map<String, Object>> sameHandle = post(
                "/api/v1/auth/register",
                Map.of(
                        "email",
                        "other-" + email,
                        "password",
                        "correct horse battery",
                        "handle",
                        handle.toUpperCase(),
                        "displayName",
                        "Dup"),
                "android",
                null);
        assertThat(sameHandle.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sameHandle.getBody().get("code")).isEqualTo("HANDLE_TAKEN");

        ResponseEntity<Map<String, Object>> reserved = post(
                "/api/v1/auth/register",
                Map.of(
                        "email",
                        "r-" + email,
                        "password",
                        "correct horse battery",
                        "handle",
                        "admin",
                        "displayName",
                        "X"),
                "android",
                null);
        assertThat(reserved.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reserved.getBody().get("code")).isEqualTo("HANDLE_RESERVED");

        ResponseEntity<Map<String, Object>> badHandle = post(
                "/api/v1/auth/register",
                Map.of(
                        "email",
                        "b-" + email,
                        "password",
                        "correct horse battery",
                        "handle",
                        "Bad-Handle!",
                        "displayName",
                        "X"),
                "android",
                null);
        assertThat(badHandle.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badHandle.getBody().get("code")).isEqualTo("HANDLE_INVALID");

        ResponseEntity<Map<String, Object>> weak = post(
                "/api/v1/auth/register",
                Map.of("email", "w-" + email, "password", "1234567890", "handle", "weak_" + handle, "displayName", "X"),
                "android",
                null);
        assertThat(weak.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(weak.getBody().get("code")).isEqualTo("PASSWORD_TOO_COMMON");

        ResponseEntity<Map<String, Object>> invalid = post(
                "/api/v1/auth/register",
                Map.of("email", "not-an-email", "password", "", "handle", "", "displayName", ""),
                "android",
                null);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
        assertThat((List<?>) invalid.getBody().get("errors")).isNotEmpty();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ResponseEntity<Map<String, Object>> post(String path, Object body, String client, HttpHeaders extra) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Client", client);
        if (extra != null) {
            headers.addAll(extra);
        }
        ResponseEntity<Map> response = rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        return (ResponseEntity) response;
    }
}
