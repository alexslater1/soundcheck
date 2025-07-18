package app.soundcheck.content;

import java.util.List;
import java.util.UUID;

/**
 * Domain event: people were tagged in a live post, either when it went live or by an edit of
 * a live post. Each of them is told once.
 */
public record PostTagged(UUID postId, UUID authorId, List<UUID> userIds) {}
