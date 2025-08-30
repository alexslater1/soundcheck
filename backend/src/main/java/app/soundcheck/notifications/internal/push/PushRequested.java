package app.soundcheck.notifications.internal.push;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Internal to the notifications module: an inbox row was written that is worth a push.
 * Published in the transaction that wrote the row, so the push goes out only once the row is committed.
 */
public record PushRequested(
        UUID notificationId, UUID userId, String type, @Nullable UUID actorId, @Nullable UUID postId) {}
