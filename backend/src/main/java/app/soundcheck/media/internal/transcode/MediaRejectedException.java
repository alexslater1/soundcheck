package app.soundcheck.media.internal.transcode;

/**
 * The uploaded file can never be transcoded (not a video, too long, too large, undecodable). The worker
 * marks the asset REJECTED and deletes the message: retrying cannot help.
 */
public class MediaRejectedException extends RuntimeException {

    public MediaRejectedException(String reason) {
        super(reason);
    }
}
