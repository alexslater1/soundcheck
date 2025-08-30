package app.soundcheck.notifications.internal.push;

import app.soundcheck.identity.UserDeleted;
import java.time.Clock;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The phone registers its FCM token after sign-in and removes it on sign-out. */
@Service
@Transactional
public class PushTokenService {

    private final PushTokenRepository tokens;
    private final Clock clock;

    PushTokenService(PushTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    public void register(UUID userId, String token, String platform) {
        tokens.save(token, userId, platform, clock.instant());
    }

    public void unregister(UUID userId, String token) {
        tokens.delete(token, userId);
    }

    /**
     * An account deleted: its sessions are revoked at once, so its phones stop getting pushes at once too,
     * not when the purge removes the user row days later (its tokens would cascade then).
     */
    @ApplicationModuleListener
    void on(UserDeleted event) {
        tokens.deleteAllOf(event.userId());
    }
}
