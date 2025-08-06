package app.soundcheck.content;

import app.soundcheck.catalog.ArtistSummary;
import app.soundcheck.identity.UserSummary;
import app.soundcheck.media.Playback;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * One clip of a post: a slot the app announced, filled with the clip once its upload is complete.
 * WAITING: the upload is not complete yet; PROCESSING: the worker has it; READY: playable; FAILED: it can
 * never play (only the clip's owner and the post's author see it). While WAITING or PROCESSING,
 * {@code previewUrl} is a frame of it, shown blurred. {@code song} is the song in it, as the user typed it.
 */
public record PostClip(
        UUID id,
        int position,
        @Schema(nullable = true) ArtistSummary act,
        @Schema(nullable = true) String song,
        UserSummary owner,
        @Schema(allowableValues = {"WAITING", "PROCESSING", "READY", "FAILED"}) String status,
        Playback playback,
        @Schema(nullable = true) String previewUrl) {

    public boolean ready() {
        return status.equals("READY");
    }
}
