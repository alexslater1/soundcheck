package app.soundcheck.notifications.internal.push;

import app.soundcheck.shared.Redacted;
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
        /**
         * The same key's JSON as text ({@code PUSH_CREDENTIALS_JSON}): on ECS secrets arrive as environment
         * variables, not files. Wins over {@code credentialsFile} when set; never printed.
         */
        @DefaultValue("") String credentialsJson,
        /** FCM's HTTP v1 API; WireMock in tests. */
        @DefaultValue("https://fcm.googleapis.com") String fcmBaseUrl,
        /**
         * Google's OAuth endpoint, where the signed assertion is traded for an access token. Blank: the key
         * file's {@code token_uri}. Not named "token…", which ConfigurationNeverPrintsSecretsTest reads as a secret.
         */
        @DefaultValue("") String oauthUrl) {

    /** Never prints the inline key. */
    @Override
    public String toString() {
        return "PushProperties[enabled=" + enabled + ", credentialsFile=" + credentialsFile + ", credentialsJson="
                + Redacted.of(credentialsJson) + ", fcmBaseUrl=" + fcmBaseUrl + ", oauthUrl=" + oauthUrl + "]";
    }
}
