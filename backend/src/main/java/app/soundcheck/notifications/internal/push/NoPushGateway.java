package app.soundcheck.notifications.internal.push;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Push is off (no Firebase project configured): every message is dropped, the inbox is unaffected. */
@Component
@ConditionalOnProperty(name = "soundcheck.push.enabled", havingValue = "false", matchIfMissing = true)
class NoPushGateway implements PushGateway {

    @Override
    public Outcome send(String token, PushMessage message) {
        return Outcome.SENT;
    }
}
