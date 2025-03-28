package app.soundcheck.content;

import app.soundcheck.catalog.ArtistSummary;
import app.soundcheck.catalog.GigSummary;
import app.soundcheck.identity.UserSummary;
import app.soundcheck.media.Playback;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code Post}: a clip with its author, caption, the gig it was filmed at, the artists tagged in
 * it, and its counters. {@code likedByMe} and the nested {@code isFollowing} / {@code myAttendance} fields
 * are relative to the viewer.
 */
public record Post(
        UUID id,
        UserSummary author,
        @Schema(nullable = true) String caption,
        @Schema(nullable = true) GigSummary gig,
        List<ArtistSummary> artists,
        Playback playback,
        int likeCount,
        int commentCount,
        boolean likedByMe,
        @Schema(nullable = true) Instant publishedAt,
        @Schema(allowableValues = {"PENDING_MEDIA", "PUBLISHED", "FAILED", "HIDDEN", "DELETED"}) String status) {}
