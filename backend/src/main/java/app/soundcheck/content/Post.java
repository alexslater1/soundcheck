package app.soundcheck.content;

import app.soundcheck.catalog.ArtistSummary;
import app.soundcheck.catalog.GigSummary;
import app.soundcheck.identity.UserSummary;
import app.soundcheck.media.Playback;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code Post}: one fan's clips from one gig and day, with its author,
 * caption, the gig, the acts tagged in it, the people they went with, and its counters. {@code playback}
 * is the cover: the first playable clip (or, while none is, the first clip). {@code clips} are in order,
 * grouped by act; a viewer other than the author sees no failed clip. {@code withUsers} are the tagged
 * people the viewer may see; {@code myTag} is the viewer's own tag (TAGGED, ON_PROFILE) or null.
 * {@code likedByMe} and the nested {@code isFollowing} / {@code myAttendance} fields are relative to the
 * viewer.
 */
public record Post(
        UUID id,
        UserSummary author,
        @Schema(nullable = true) String caption,
        @Schema(nullable = true) GigSummary gig,
        @Schema(nullable = true) LocalDate day,
        List<ArtistSummary> artists,
        Playback playback,
        List<PostClip> clips,
        int clipCount,
        List<UserSummary> withUsers,
        @Schema(
                        nullable = true,
                        allowableValues = {"TAGGED", "ON_PROFILE"})
                String myTag,
        int likeCount,
        int commentCount,
        boolean likedByMe,
        @Schema(nullable = true) Instant publishedAt,
        @Schema(allowableValues = {"PENDING_MEDIA", "PUBLISHED", "FAILED", "HIDDEN", "DELETED"}) String status) {}
