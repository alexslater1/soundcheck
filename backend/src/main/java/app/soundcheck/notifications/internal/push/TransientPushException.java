package app.soundcheck.notifications.internal.push;

/** FCM or Google's token endpoint was busy or unreachable (429, 5xx, I/O): worth a retry. */
class TransientPushException extends RuntimeException {

    TransientPushException(String message) {
        super(message);
    }

    TransientPushException(String message, Throwable cause) {
        super(message, cause);
    }
}
