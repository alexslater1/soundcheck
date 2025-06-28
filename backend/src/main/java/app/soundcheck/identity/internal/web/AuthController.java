package app.soundcheck.identity.internal.web;

import app.soundcheck.identity.UserSummary;
import app.soundcheck.identity.Users;
import app.soundcheck.identity.internal.auth.AuthService;
import app.soundcheck.shared.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth endpoints. Transport of the refresh token depends on {@code X-Client}:
 * {@code android} gets it in the body (stored in the Keystore-backed secure store); {@code web} gets the
 * {@code soundcheck_rt} cookie — HttpOnly, Secure, SameSite=Strict, scoped to {@code /api/v1/auth} — and
 * must send {@code X-Requested-With: soundcheck} on refresh (CSRF belt and braces).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "auth", description = "Register, login, refresh, logout")
public class AuthController {

    static final String REFRESH_COOKIE = "soundcheck_rt";
    static final String REQUESTED_WITH = "soundcheck";
    static final String COOKIE_PATH = "/api/v1/auth";

    private final AuthService auth;
    private final Users users;
    private final long refreshCookieMaxAgeSeconds;

    public AuthController(
            AuthService auth, Users users, app.soundcheck.identity.internal.auth.AuthProperties properties) {
        this.auth = auth;
        this.users = users;
        this.refreshCookieMaxAgeSeconds = properties.jwt().refreshTtl().toSeconds();
    }

    @PostMapping("/register")
    @Operation(operationId = "register", summary = "Create an account and start a session")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest body,
            @RequestHeader(name = "X-Client", defaultValue = "web") String client,
            @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) @Nullable String userAgent) {
        AuthService.Session session =
                auth.register(body.email(), body.password(), body.handle(), body.displayName(), client, userAgent);
        return respond(HttpStatus.CREATED, session, client);
    }

    @PostMapping("/login")
    @Operation(operationId = "login", summary = "Sign in")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest body,
            @RequestHeader(name = "X-Client", defaultValue = "web") String client,
            @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) @Nullable String userAgent) {
        return respond(HttpStatus.OK, auth.login(body.email(), body.password(), client, userAgent), client);
    }

    @PostMapping("/refresh")
    @Operation(operationId = "refresh", summary = "Rotate the refresh token and get a new access token")
    public ResponseEntity<AuthResponse> refresh(
            @RequestBody(required = false) @Valid @Nullable RefreshRequest body,
            @CookieValue(name = REFRESH_COOKIE, required = false) @Nullable String cookie,
            @RequestHeader(name = "X-Client", defaultValue = "web") String client,
            @RequestHeader(name = "X-Requested-With", required = false) @Nullable String requestedWith,
            @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) @Nullable String userAgent) {
        String raw;
        if (client.equals("web")) {
            if (!REQUESTED_WITH.equals(requestedWith)) {
                throw ApiException.forbidden("CSRF_HEADER_MISSING", "X-Requested-With: soundcheck is required");
            }
            raw = cookie;
        } else {
            raw = body == null ? null : body.refreshToken();
        }
        if (raw == null || raw.isBlank()) {
            throw ApiException.unauthorized("REFRESH_MISSING", "No refresh token presented");
        }
        return respond(HttpStatus.OK, auth.refresh(raw, client, userAgent), client);
    }

    @PostMapping("/logout")
    @Operation(operationId = "logout", summary = "Revoke the session's refresh-token family")
    public ResponseEntity<Void> logout(
            @RequestBody(required = false) @Valid @Nullable RefreshRequest body,
            @CookieValue(name = REFRESH_COOKIE, required = false) @Nullable String cookie,
            @RequestHeader(name = "X-Client", defaultValue = "web") String client) {
        auth.logout(client.equals("web") ? cookie : body == null ? null : body.refreshToken());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.NO_CONTENT);
        if (client.equals("web")) {
            response.header(HttpHeaders.SET_COOKIE, refreshCookie("", 0).toString());
        }
        return response.build();
    }

    private ResponseEntity<AuthResponse> respond(HttpStatus status, AuthService.Session session, String client) {
        boolean web = client.equals("web");
        AuthResponse body = new AuthResponse(
                session.accessToken(),
                session.expiresIn(),
                web ? null : session.refreshToken(),
                users.summary(session.user().id())
                        .orElseGet(() -> new UserSummary(
                                session.user().id(),
                                session.user().handle(),
                                session.user().displayName(),
                                null,
                                null)));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (web) {
            response.header(
                    HttpHeaders.SET_COOKIE,
                    refreshCookie(session.refreshToken(), refreshCookieMaxAgeSeconds)
                            .toString());
        }
        return response.body(body);
    }

    static ResponseCookie refreshCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAgeSeconds)
                .build();
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password,
            @NotBlank @Size(min = 3, max = 30) String handle,
            @NotBlank @Size(min = 1, max = 50) String displayName) {}

    public record LoginRequest(
            @NotBlank @Email @Size(min = 1, max = 254) String email, @NotBlank @Size(max = 128) String password) {}

    /** The Android refresh token (43 characters, base64url of 256 bits); bounded so nothing large is hashed. */
    public record RefreshRequest(@Schema(nullable = true) @Size(max = 128) String refreshToken) {}

    /** Session response: {@code refreshToken} is present for Android only. */
    public record AuthResponse(
            String accessToken, long expiresIn, @Schema(nullable = true) String refreshToken, UserSummary user) {}
}
