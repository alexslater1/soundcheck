package app.soundcheck.media;

import java.util.UUID;

/** The facts about an asset that other modules decide on: whose it is, what it is, how far along it is. */
public record MediaInfo(UUID id, UUID ownerId, String kind, String status) {

    public boolean video() {
        return kind.equals("VIDEO");
    }

    public boolean ready() {
        return status.equals("READY");
    }

    /** Processing ended without a playable result: REJECTED, FAILED or DELETED. */
    public boolean unusable() {
        return status.equals("REJECTED") || status.equals("FAILED") || status.equals("DELETED");
    }
}
