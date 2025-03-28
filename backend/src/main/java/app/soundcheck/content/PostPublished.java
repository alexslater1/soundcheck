package app.soundcheck.content;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event: a post became visible. {@code afterProcessing} is true when it had been
 * waiting for its clip to be transcoded (the author is told it is ready), false when the clip was ready
 * at the moment of posting.
 */
public record PostPublished(UUID postId, UUID authorId, Instant publishedAt, boolean afterProcessing) {}
