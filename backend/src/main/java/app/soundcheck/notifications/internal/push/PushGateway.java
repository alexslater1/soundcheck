package app.soundcheck.notifications.internal.push;

/** Sends one message to one device: FCM, or nothing when push is off. */
public interface PushGateway {

    enum Outcome {
        SENT,
        /** FCM no longer knows the token (app uninstalled, data cleared): the caller deletes it. */
        TOKEN_GONE
    }

    Outcome send(String token, PushMessage message);
}
