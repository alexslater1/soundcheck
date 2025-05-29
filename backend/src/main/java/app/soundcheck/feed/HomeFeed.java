package app.soundcheck.feed;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * A page of the ranked home feed. {@code sessionId} names the ranked list the page was cut
 * from; the client sends it with its interaction events. {@code nextCursor} continues the same list.
 */
public record HomeFeed(UUID sessionId, List<FeedItem> items, @Schema(nullable = true) String nextCursor) {}
