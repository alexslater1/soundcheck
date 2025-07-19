package app.soundcheck.content.internal;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A clip as the client announces it in a post: its video if the upload is already complete, the
 * act on stage, and a frame shown blurred while the clip is on its way.
 */
public record NewClip(@Nullable UUID mediaId, @Nullable UUID artistId, @Nullable UUID previewMediaId) {}
