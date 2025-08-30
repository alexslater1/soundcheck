package app.soundcheck.notifications.internal.push;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code soundcheck.push.*}: push notifications through Firebase Cloud Messaging. Off unless
 * {@code enabled}: without a Firebase project there is nothing to send to, and the inbox works as before.
 */
@ConfigurationProperties(prefix = "soundcheck.push")
public record PushProperties(
        @DefaultValue("false") boolean enabled,
        /** The Firebase service account's JSON key (Project settings → Service accounts); never committed. */
        @DefaultValue("") String credentialsFile,
        /** FCM's HTTP v1 API; WireMock in tests. */
        @DefaultValue("https://fcm.googleapis.com") String fcmBaseUrl,
        /** Where the signed assertion is exchanged for an access token; the key file's own value wins. */
        @DefaultValue("") String tokenUri) {}
