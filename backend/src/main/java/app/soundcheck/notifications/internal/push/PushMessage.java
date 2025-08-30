package app.soundcheck.notifications.internal.push;

import java.util.Map;

/** What a phone shows: a title, a line of text, and the data the app opens when it is tapped. */
public record PushMessage(String title, String body, Map<String, String> data) {}
