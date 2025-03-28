package app.soundcheck.support;

import app.soundcheck.catalog.ArtistDraft;
import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.catalog.GigDraft;
import app.soundcheck.catalog.VenueDraft;
import app.soundcheck.media.MediaFailed;
import app.soundcheck.media.MediaReady;
import app.soundcheck.shared.Ids;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds the things other tests need as a starting point: a gig with a bill (through catalog's
 * own write API), and media assets in a given state (straight into the table: the upload and transcode
 * paths have their own tests, and running FFmpeg for every post test would only make them slow).
 */
public final class TestData {

    private final CatalogIngest catalog;
    private final DSLContext dsl;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public TestData(CatalogIngest catalog, DSLContext dsl, TransactionTemplate tx, ApplicationEventPublisher events) {
        this.catalog = catalog;
        this.dsl = dsl;
        this.tx = tx;
        this.events = events;
    }

    public UUID venue(String name) {
        return tx.execute(status -> catalog.createVenue(
                new VenueDraft(name, "1 Test Street", "Manchester", "M1 1AA", 53.4808, -2.2426, "Europe/London")));
    }

    public UUID artist(String name) {
        return tx.execute(status -> catalog.createArtist(new ArtistDraft(name, null, null, Map.of())));
    }

    /** A scheduled gig with the artists in billing order (headliner first). */
    public UUID gig(String title, UUID venueId, Instant startsAt, UUID... artistIds) {
        return tx.execute(status -> catalog.createGig(new GigDraft(
                title,
                venueId,
                startsAt,
                null,
                "SCHEDULED",
                null,
                null,
                null,
                null,
                null,
                Ids.newId().toString().getBytes(),
                List.of(artistIds))));
    }

    /** A video asset owned by the user, in the given status (READY ones get outputs to point at). */
    public UUID video(UUID ownerId, String status) {
        return media(ownerId, "VIDEO", "video/mp4", status);
    }

    public UUID image(UUID ownerId, String status) {
        return media(ownerId, "IMAGE", "image/png", status);
    }

    private UUID media(UUID ownerId, String kind, String contentType, String status) {
        UUID id = Ids.newId();
        dsl.execute(
                "insert into media.media_asset (id, owner_id, kind, status, content_type, declared_bytes, original_key,"
                        + " upload_strategy) values (?, ?, ?, 'UPLOADED', ?, 1000, ?, 'SINGLE')",
                id,
                ownerId,
                kind,
                contentType,
                "uploads/" + ownerId + "/" + id + "/original");
        if (!status.equals("UPLOADED")) {
            setStatus(id, status);
        }
        return id;
    }

    /** Puts an asset into a status the way the pipeline would leave it. */
    public void setStatus(UUID mediaId, String status) {
        if (status.equals("READY")) {
            String prefix = "media/" + mediaId + "/v1/";
            dsl.execute(
                    "update media.media_asset set status = 'READY', output_prefix = ?, poster_key = ?, thumb_key = ?,"
                            + " blurhash = 'LKTLyT|{fQ|{|{oLfQoLfQfQfQfQ', duration_ms = 5000, width = 720, height = 1280,"
                            + " ready_at = now() where id = ?",
                    prefix,
                    prefix + (isVideo(mediaId) ? "poster.jpg" : "image.png"),
                    prefix + (isVideo(mediaId) ? "thumb.webp" : "image.png"),
                    mediaId);
        } else {
            dsl.execute("update media.media_asset set status = ? where id = ?", status, mediaId);
        }
    }

    /** What the worker does when a transcode succeeds: the asset is READY and {@code MediaReady} is published. */
    public void workerFinishes(UUID mediaId, UUID ownerId) {
        tx.executeWithoutResult(status -> {
            setStatus(mediaId, "READY");
            events.publishEvent(new MediaReady(mediaId, ownerId, Instant.now()));
        });
    }

    /** What the worker does when a clip is rejected or out of attempts. */
    public void workerGivesUp(UUID mediaId, UUID ownerId, boolean rejected) {
        tx.executeWithoutResult(status -> {
            setStatus(mediaId, rejected ? "REJECTED" : "FAILED");
            events.publishEvent(new MediaFailed(mediaId, ownerId, "forced by the test", rejected, Instant.now()));
        });
    }

    private boolean isVideo(UUID mediaId) {
        return "VIDEO".equals(dsl.fetchValue("select kind from media.media_asset where id = ?", mediaId));
    }
}
