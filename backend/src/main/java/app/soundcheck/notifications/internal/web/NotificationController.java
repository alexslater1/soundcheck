package app.soundcheck.notifications.internal.web;

import app.soundcheck.notifications.internal.NotificationService;
import app.soundcheck.notifications.internal.NotificationService.Inbox;
import app.soundcheck.notifications.internal.NotificationService.NotificationView;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The notification inbox. */
@ConditionalOnRole(Roles.API)
@RestController
@RequestMapping("/api/v1/notifications")
@Validated
@Tag(name = "notifications", description = "The notification inbox")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @Operation(operationId = "listNotifications", summary = "The inbox, newest first, with the unread count")
    public Inbox inbox(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
        return notifications.inbox(UUID.fromString(jwt.getSubject()), cursor, limit);
    }

    @GetMapping("/{id}")
    @Operation(
            operationId = "getNotification",
            summary = "One of the viewer's notifications (a new-gig alert's page opens it); 404 if not theirs")
    public NotificationView one(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return notifications.one(UUID.fromString(jwt.getSubject()), id);
    }

    @PostMapping("/read")
    @Operation(operationId = "markNotificationsRead", summary = "Mark everything up to a notification as read")
    public UnreadResponse read(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReadRequest body) {
        return new UnreadResponse(notifications.readUpTo(UUID.fromString(jwt.getSubject()), body.upTo()));
    }

    public record ReadRequest(@NotNull UUID upTo) {}

    public record UnreadResponse(long unreadCount) {}
}
