package app.soundcheck.notifications.internal.push;

import app.soundcheck.identity.UserSummary;
import app.soundcheck.identity.Users;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Pushes a new inbox row to the user's phones. Runs like the other listeners: after the row's
 * transaction has committed, off the request thread. The wording is the Activity tab's
 * ({@code notificationText.ts}), so a push reads the same as the entry it opens. A failed send is
 * counted and dropped rather than retried later: a late push is worse than none, and the inbox has it.
 */
@Component
class PushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);

    /** The kinds that are about the user personally. Gig alerts and new-post roll-ups stay in the inbox. */
    static final Set<String> PUSHED = Set.of(
            "NEW_FOLLOWER",
            "FOLLOW_REQUEST",
            "FOLLOW_ACCEPTED",
            "POST_LIKED",
            "POST_COMMENTED",
            "POST_TAGGED",
            "POST_READY",
            "POST_FAILED");

    private final PushTokenRepository tokens;
    private final PushGateway gateway;
    private final Users users;
    private final MeterRegistry meters;

    PushDispatcher(PushTokenRepository tokens, PushGateway gateway, Users users, MeterRegistry meters) {
        this.tokens = tokens;
        this.gateway = gateway;
        this.users = users;
        this.meters = meters;
    }

    @ApplicationModuleListener
    void on(PushRequested event) {
        if (!PUSHED.contains(event.type())) {
            return;
        }
        var devices = tokens.tokensOf(event.userId());
        if (devices.isEmpty()) {
            return;
        }
        PushMessage message = message(event);
        for (String token : devices) {
            String outcome;
            try {
                if (gateway.send(token, message) == PushGateway.Outcome.TOKEN_GONE) {
                    tokens.delete(token);
                    outcome = "token_gone";
                } else {
                    outcome = "sent";
                }
            } catch (RuntimeException e) {
                // Never the token: it identifies a device.
                log.warn("push of {} failed: {}", event.type(), e.getMessage());
                outcome = "failed";
            }
            meters.counter("soundcheck.push.messages", "type", event.type(), "outcome", outcome)
                    .increment();
        }
    }

    PushMessage message(PushRequested event) {
        String who = event.actorId() == null
                ? "Someone"
                : users.summary(event.actorId())
                        .map(UserSummary::handle)
                        .map(handle -> "@" + handle)
                        .orElse("Someone");
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", event.type());
        data.put("notificationId", event.notificationId().toString());
        if (event.postId() != null) {
            data.put("postId", event.postId().toString());
        }
        return new PushMessage("Soundcheck", text(event.type(), who), data);
    }

    static String text(String type, @Nullable String who) {
        return switch (type) {
            case "NEW_FOLLOWER" -> who + " started following you";
            case "FOLLOW_REQUEST" -> who + " asked to follow you";
            case "FOLLOW_ACCEPTED" -> who + " accepted your follow request";
            case "POST_LIKED" -> who + " liked your clip";
            case "POST_COMMENTED" -> who + " commented on your clip";
            case "POST_TAGGED" -> who + " tagged you in their post";
            case "POST_READY" -> "Your clip is ready";
            case "POST_FAILED" -> "Your clip could not be processed";
            default -> "Something new in Activity";
        };
    }
}
