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
 * The /me round trip (profile, city, genres, onboarding), handle validation and reserved
 * words, public profile, delete with password confirm + revoked sessions, admin bootstrap at startup.
 */
class MeApiIT extends IntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void meProfileGenresOnboardingAndDelete() {
        String email = "me-" + UUID.randomUUID() + "@example.com";
        String handle = "me_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        Map<String, Object> session = register(email, handle);
        String access = (String) session.get("accessToken");
        String refresh = (String) session.get("refreshToken");

        Map<String, Object> me =
                call(HttpMethod.GET, "/api/v1/me", null, access).getBody();
        assertThat(me)
                .containsEntry("handle", handle)
                .containsEntry("email", email)
                .containsEntry("onboarded", false);
        assertThat((List<?>) me.get("genres")).isEmpty();
        assertThat(me.get("homeCity")).isNull();

        // City id for Manchester from the public autocomplete.
        List<Map<String, Object>> cities = rest.exchange("/api/v1/cities?q=manchester", HttpMethod.GET, null, LIST)
                .getBody();
        int manchester = (Integer) cities.getFirst().get("id");

        ResponseEntity<Map<String, Object>> patched = call(
                HttpMethod.PATCH,
                "/api/v1/me",
                Map.of("displayName", "Fan One", "bio", "Loves loud rooms", "homeCityId", manchester),
                access);
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) patched.getBody().get("homeCity")).get("name")).isEqualTo("Manchester");
        assertThat(patched.getBody()).containsEntry("displayName", "Fan One").containsEntry("bio", "Loves loud rooms");

        assertThat(call(HttpMethod.PATCH, "/api/v1/me", Map.of("homeCityId", 1), access)
                        .getBody()
                        .get("code"))
                .isEqualTo("CITY_UNKNOWN");
        assertThat(call(HttpMethod.PATCH, "/api/v1/me", Map.of("handle", "admin"), access)
                        .getBody()
                        .get("code"))
                .isEqualTo("HANDLE_RESERVED");
        assertThat(call(HttpMethod.PATCH, "/api/v1/me", Map.of("handle", "Not Valid"), access)
                        .getBody()
                        .get("code"))
                .isEqualTo("HANDLE_INVALID");
        ResponseEntity<Map<String, Object>> renamed =
                call(HttpMethod.PATCH, "/api/v1/me", Map.of("handle", handle + "x"), access);
        assertThat(renamed.getBody().get("handle")).isEqualTo(handle + "x");

        ResponseEntity<Map<String, Object>> genres = call(
                HttpMethod.PUT,
                "/api/v1/me/genres",
                Map.of("genres", List.of(Map.of("genreId", 2, "weight", 1.0), Map.of("genreId", 14, "weight", 0.5))),
                access);
        assertThat(genres.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) genres.getBody().get("genres")).hasSize(2);
        assertThat(call(HttpMethod.PUT, "/api/v1/me/genres", Map.of("genres", List.of()), access)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(
                                HttpMethod.PUT,
                                "/api/v1/me/genres",
                                Map.of("genres", List.of(Map.of("genreId", 999, "weight", 1.0))),
                                access)
                        .getBody()
                        .get("code"))
                .isEqualTo("GENRE_UNKNOWN");

        ResponseEntity<Map<String, Object>> onboarded =
                call(HttpMethod.POST, "/api/v1/me/onboarding/complete", null, access);
        assertThat(onboarded.getBody()).containsEntry("onboarded", true);

        Map<String, Object> profile = call(HttpMethod.GET, "/api/v1/users/" + handle + "x", null, access)
                .getBody();
        assertThat(profile).containsEntry("displayName", "Fan One").containsEntry("homeCityName", "Manchester");
        assertThat(profile).doesNotContainKey("email");
        assertThat(call(HttpMethod.GET, "/api/v1/users/nobody_here", null, access)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(call(HttpMethod.DELETE, "/api/v1/me", Map.of("password", "wrong password!"), access)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.DELETE, "/api/v1/me", Map.of("password", "correct horse battery"), access)
                        .getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        // Sessions are revoked, the account is gone from /me and profiles, and login no longer works.
        assertThat(call(HttpMethod.POST, "/api/v1/auth/refresh", Map.of("refreshToken", refresh), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.GET, "/api/v1/me", null, access).getBody().get("code"))
                .isEqualTo("ACCOUNT_DELETED");
        assertThat(call(HttpMethod.GET, "/api/v1/users/" + handle + "x", null, access)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(
                                HttpMethod.POST,
                                "/api/v1/auth/login",
                                Map.of("email", email, "password", "correct horse battery"),
                                null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminAccountExistsAfterStartupWithTheAdminRole() {
        ResponseEntity<Map<String, Object>> login = call(
                HttpMethod.POST,
                "/api/v1/auth/login",
                Map.of("email", "admin@test.local", "password", "admin-test-passphrase"),
                null);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String access = (String) login.getBody().get("accessToken");
        Map<String, Object> me =
                call(HttpMethod.GET, "/api/v1/me", null, access).getBody();
        assertThat(me).containsEntry("role", "ADMIN").containsEntry("handle", "admin");
    }

    /**
     * Only a few endpoints work without a token; {@code /users/{handle}} is not one of them
     * (its response carries viewer-relative fields, {@code isFollowing}/{@code isBlocked}).
     */
    @Test
    void publicProfilesStillNeedASignedInViewer() {
        String handle = "pv_" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> session = register("pv-" + UUID.randomUUID() + "@example.com", handle);

        ResponseEntity<Map<String, Object>> anonymous = call(HttpMethod.GET, "/api/v1/users/" + handle, null, null);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.getBody()).containsEntry("code", "UNAUTHENTICATED");

        ResponseEntity<Map<String, Object>> signedIn =
                call(HttpMethod.GET, "/api/v1/users/" + handle, null, (String) session.get("accessToken"));
        assertThat(signedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signedIn.getBody()).containsEntry("handle", handle).doesNotContainKey("email");
    }

    static final org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new org.springframework.core.ParameterizedTypeReference<>() {};

    private Map<String, Object> register(String email, String handle) {
        ResponseEntity<Map<String, Object>> response = call(
                HttpMethod.POST,
                "/api/v1/auth/register",
                Map.of("email", email, "password", "correct horse battery", "handle", handle, "displayName", "Fan"),
                null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, Object body, String access) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Client", "android");
        if (access != null) {
            headers.setBearerAuth(access);
        }
        ResponseEntity<Map> response = rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
        return (ResponseEntity) response;
    }
}
