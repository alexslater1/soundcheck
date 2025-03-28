package app.soundcheck.content;

import java.util.UUID;

/** Domain event: a like was actually added (a repeated like publishes nothing). */
public record PostLiked(UUID postId, UUID postAuthorId, UUID likerId) {}
