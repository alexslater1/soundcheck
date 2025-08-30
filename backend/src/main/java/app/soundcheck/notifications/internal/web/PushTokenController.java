package app.soundcheck.notifications.internal.web;

import app.soundcheck.notifications.internal.push.PushTokenService;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The phone's push token. The token goes in the body, not the path, so it stays out of access
 * logs. Registering the same token again is harmless; a token registered by another account moves here.
 */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1/notifications/push-token")
@Validated
@Tag(name = "notifications", description = "The notification inbox")
public class PushTokenController {

    private final PushTokenService tokens;

    public PushTokenController(PushTokenService tokens) {
        this.tokens = tokens;
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "registerPushToken", summary = "Push this phone's notifications to it (FCM token)")
    public void register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PushTokenRequest body) {
        tokens.register(UUID.fromString(jwt.getSubject()), body.token(), body.platform());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "removePushToken", summary = "Stop pushing to this phone (on sign-out)")
    public void remove(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PushTokenRemoval body) {
        tokens.unregister(UUID.fromString(jwt.getSubject()), body.token());
    }

    public record PushTokenRequest(
            @NotBlank @Size(max = 4096) String token, @NotBlank @Pattern(regexp = "android") String platform) {}

    public record PushTokenRemoval(@NotBlank @Size(max = 4096) String token) {}
}
