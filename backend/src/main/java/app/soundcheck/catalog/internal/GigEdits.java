package app.soundcheck.catalog.internal;

import static app.soundcheck.shared.jooq.catalog.Tables.ARTIST;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG;
import static app.soundcheck.shared.jooq.catalog.Tables.GIG_EDIT;
import static app.soundcheck.shared.jooq.catalog.Tables.VENUE;

import app.soundcheck.catalog.GigClips;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.jooq.catalog.tables.records.GigEditRecord;
import app.soundcheck.shared.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A gig's edit history and the operator's undo. Fans' edits ({@code catalog.gig_edit})
 * keep the value before and after, so an undo puts the old value back and is logged as an edit of
 * the same field pointing at the one it undoes (V152). Undoing an undo puts the edit back.
 *
 * <p>An undo is refused when a later edit of the gig touched the same thing ({@code EDIT_SUPERSEDED}):
 * putting the old value back would silently throw away the later one. "The same thing" is the field,
 * except that STARTS_AT and DATE both change the start, and each ACT_ADDED is about its own act.
 */
@Service
@ConditionalOnRole(Roles.API)
public class GigEdits {

    /** A gig has a handful of edits; this caps a runaway one. */
    static final int MAX_HISTORY = 200;

    @Schema(name = "GigEdit")
    public record EditView(
            UUID id,
            UUID gigId,
            @Schema(nullable = true, description = "Who made it; null once their account is purged") UUID userId,
            @Schema(allowableValues = {"ACT_ADDED", "STARTS_AT", "DATE", "VENUE", "HEADLINER", "TITLE", "MERGE"})
                    String field,
            @Schema(nullable = true) String oldValue,
            @Schema(nullable = true, description = "Null only for an undo that took an act off the bill")
                    String newValue,
            @Schema(nullable = true, description = "The act's or venue's name for an id value") String oldLabel,
            @Schema(nullable = true) String newLabel,
            Instant createdAt,
            @Schema(nullable = true, description = "The edit this one undoes") UUID undoesEditId,
            @Schema(nullable = true, description = "The latest edit that undid this one") UUID undoneByEditId,
            @Schema(description = "False for a merge, which can't be undone") boolean undoable) {}

    private final DSLContext dsl;
    private final SubmissionRepository submissions;
    private final CatalogReadRepository reads;
    private final GigClips clips;
    private final Clock clock;

    public GigEdits(
            DSLContext dsl,
            SubmissionRepository submissions,
            CatalogReadRepository reads,
            GigClips clips,
            Clock clock) {
        this.dsl = dsl;
        this.submissions = submissions;
        this.reads = reads;
        this.clips = clips;
        this.clock = clock;
    }

    /**
     * The gig's edits, newest first (a merged gig's id answers with the survivor's, where its edits went).
     * Expected index: gig_edit_gig (gig_id, created_at).
     *
     * @throws ApiException 404 {@code GIG_NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public List<EditView> history(UUID requested) {
        UUID gigId = liveGig(requested);
        List<GigEditRecord> rows = dsl.selectFrom(GIG_EDIT)
                .where(GIG_EDIT.GIG_ID.eq(gigId))
                .orderBy(GIG_EDIT.CREATED_AT.desc(), GIG_EDIT.ID.desc())
                .limit(MAX_HISTORY)
                .fetch();
        Map<UUID, UUID> undoneBy = new HashMap<>();
        for (GigEditRecord row : rows) { // newest first: the first undo seen is the latest
            if (row.getUndoesEditId() != null) {
                undoneBy.putIfAbsent(row.getUndoesEditId(), row.getId());
            }
        }
        Map<String, String> labels = labels(rows);
        return rows.stream().map(r -> view(r, labels, undoneBy.get(r.getId()))).toList();
    }

    /**
     * Puts the edit's old value back, and logs that as an edit of the same field.
     *
     * @throws ApiException 404 {@code EDIT_NOT_FOUND}; 409 {@code MERGE_NOT_UNDOABLE}, {@code
     *     EDIT_SUPERSEDED} (naming the later edit), {@code ACT_HAS_CLIPS}, {@code EDIT_NOT_UNDOABLE} (no
     *     value to go back to: the act or venue is gone, or there was no headliner)
     */
    @Transactional
    public EditView undo(UUID editId, UUID adminId) {
        GigEditRecord edit = dsl.selectFrom(GIG_EDIT)
                .where(GIG_EDIT.ID.eq(editId))
                .fetchOptional()
                .orElseThrow(() -> ApiException.notFound("EDIT_NOT_FOUND", "No gig edit " + editId));
        UUID gigId = edit.getGigId();
        // One undo of a gig at a time: two at once could each see no later edit and both apply.
        dsl.select(GIG.ID).from(GIG).where(GIG.ID.eq(gigId)).forUpdate().fetch();
        if ("MERGE".equals(edit.getField())) {
            throw ApiException.conflict("MERGE_NOT_UNDOABLE", "A merge can't be undone: the merged gig is gone");
        }
        GigEditRecord later = dsl.selectFrom(GIG_EDIT)
                .where(GIG_EDIT.GIG_ID.eq(gigId))
                .and(DSL.row(GIG_EDIT.CREATED_AT, GIG_EDIT.ID).gt(DSL.row(edit.getCreatedAt(), edit.getId())))
                .and(sameThing(edit))
                .orderBy(GIG_EDIT.CREATED_AT.desc(), GIG_EDIT.ID.desc())
                .limit(1)
                .fetchOne();
        if (later != null) {
            throw ApiException.conflict(
                    "EDIT_SUPERSEDED",
                    "A later edit changed the same thing (" + later.getField() + ", edit " + later.getId()
                            + "); undo that one first");
        }
        Instant now = clock.instant();
        UUID undo =
                switch (edit.getField()) {
                    case "ACT_ADDED" -> undoAct(edit, adminId, now);
                    case "STARTS_AT", "DATE" -> {
                        submissions.setStartsAt(gigId, instant(edit.getOldValue()), now);
                        yield logReverse(edit, adminId, now);
                    }
                    case "VENUE" -> {
                        UUID venue = venueNow(required(edit.getOldValue()));
                        submissions.setVenue(gigId, venue, now);
                        yield submissions.logEdit(
                                gigId, adminId, "VENUE", edit.getNewValue(), venue.toString(), now, edit.getId());
                    }
                    case "HEADLINER" -> {
                        UUID artist = UUID.fromString(required(edit.getOldValue()));
                        if (reads.lineups(List.of(gigId)).stream()
                                .noneMatch(entry -> entry.artistId().equals(artist))) {
                            throw ApiException.conflict(
                                    "EDIT_NOT_UNDOABLE", "The old headliner is no longer on the bill");
                        }
                        submissions.setHeadliner(gigId, artist);
                        yield logReverse(edit, adminId, now);
                    }
                    case "TITLE" -> {
                        submissions.setTitle(gigId, required(edit.getOldValue()), now);
                        yield logReverse(edit, adminId, now);
                    }
                    default -> throw ApiException.conflict("EDIT_NOT_UNDOABLE", "Unknown field " + edit.getField());
                };
        return history(gigId).stream()
                .filter(e -> e.id().equals(undo))
                .findFirst()
                .orElseThrow();
    }

    /**
     * An ACT_ADDED with no old value added the act: the undo takes it off the bill, unless a clip is of
     * that act. One with an old value (an undo that took it off) puts it back.
     */
    private UUID undoAct(GigEditRecord edit, UUID adminId, Instant now) {
        UUID gigId = edit.getGigId();
        if (edit.getOldValue() == null) {
            UUID act = UUID.fromString(required(edit.getNewValue()));
            long onClips = clips.clipsOfActAt(gigId, act);
            if (onClips > 0) {
                throw ApiException.conflict(
                        "ACT_HAS_CLIPS",
                        onClips + (onClips == 1 ? " clip is" : " clips are") + " of this act at this gig; it stays");
            }
            if (reads.lineups(List.of(gigId)).size() <= 1) {
                throw ApiException.conflict("EDIT_NOT_UNDOABLE", "The act is the only one on the bill");
            }
            submissions.removeAct(gigId, act);
            return submissions.logEdit(gigId, adminId, "ACT_ADDED", act.toString(), null, now, edit.getId());
        }
        UUID act = UUID.fromString(edit.getOldValue());
        if (!dsl.fetchExists(ARTIST, ARTIST.ID.eq(act))) {
            throw ApiException.conflict("EDIT_NOT_UNDOABLE", "The act no longer exists");
        }
        submissions.addAct(gigId, act, adminId);
        return submissions.logEdit(gigId, adminId, "ACT_ADDED", null, act.toString(), now, edit.getId());
    }

    /** The undo of a single-valued field: from what the edit set, back to what it replaced. */
    private UUID logReverse(GigEditRecord edit, UUID adminId, Instant now) {
        return submissions.logEdit(
                edit.getGigId(), adminId, edit.getField(), edit.getNewValue(), edit.getOldValue(), now, edit.getId());
    }

    /** Later edits that touched what this one touched (see the class comment). */
    private static Condition sameThing(GigEditRecord edit) {
        return switch (edit.getField()) {
            case "STARTS_AT", "DATE" -> GIG_EDIT.FIELD.in("STARTS_AT", "DATE");
            case "ACT_ADDED" -> {
                String act = Objects.requireNonNullElse(edit.getNewValue(), edit.getOldValue());
                yield GIG_EDIT.FIELD
                        .eq("ACT_ADDED")
                        .and(GIG_EDIT.NEW_VALUE.eq(act).or(GIG_EDIT.OLD_VALUE.eq(act)));
            }
            default -> GIG_EDIT.FIELD.eq(edit.getField());
        };
    }

    /** The venue the edit moved the gig from. */
    private UUID venueNow(String value) {
        UUID venue = UUID.fromString(value);
        if (reads.venueExists(venue)) {
            return venue;
        }
        throw ApiException.conflict("EDIT_NOT_UNDOABLE", "The old venue no longer exists");
    }

    private UUID liveGig(UUID requested) {
        if (reads.gigExists(requested)) {
            return requested;
        }
        return reads.redirectOf(requested)
                .orElseThrow(() -> ApiException.notFound("GIG_NOT_FOUND", "No gig " + requested));
    }

    /** Names for the id values: acts (ACT_ADDED, HEADLINER) and venues (VENUE). */
    private Map<String, String> labels(List<GigEditRecord> rows) {
        Set<UUID> artists = new HashSet<>();
        Set<UUID> venues = new HashSet<>();
        for (GigEditRecord r : rows) {
            Set<UUID> into =
                    switch (r.getField()) {
                        case "ACT_ADDED", "HEADLINER" -> artists;
                        case "VENUE" -> venues;
                        default -> null;
                    };
            if (into != null) {
                for (String value : new String[] {r.getOldValue(), r.getNewValue()}) {
                    if (value != null) {
                        into.add(UUID.fromString(value));
                    }
                }
            }
        }
        Map<String, String> labels = new HashMap<>();
        if (!artists.isEmpty()) {
            dsl.select(ARTIST.ID, ARTIST.NAME)
                    .from(ARTIST)
                    .where(ARTIST.ID.in(artists))
                    .forEach(r -> labels.put(r.value1().toString(), r.value2()));
        }
        if (!venues.isEmpty()) {
            dsl.select(VENUE.ID, VENUE.NAME)
                    .from(VENUE)
                    .where(VENUE.ID.in(venues))
                    .forEach(r -> labels.put(r.value1().toString(), r.value2()));
        }
        return labels;
    }

    private static EditView view(GigEditRecord r, Map<String, String> labels, @Nullable UUID undoneBy) {
        return new EditView(
                r.getId(),
                r.getGigId(),
                r.getUserId(),
                r.getField(),
                r.getOldValue(),
                r.getNewValue(),
                r.getOldValue() == null ? null : labels.get(r.getOldValue()),
                r.getNewValue() == null ? null : labels.get(r.getNewValue()),
                r.getCreatedAt().toInstant(),
                r.getUndoesEditId(),
                undoneBy,
                !"MERGE".equals(r.getField()));
    }

    private static String required(@Nullable String value) {
        if (value == null) {
            throw ApiException.conflict("EDIT_NOT_UNDOABLE", "There is no earlier value to go back to");
        }
        return value;
    }

    private static Instant instant(@Nullable String value) {
        try {
            return Instant.parse(required(value));
        } catch (DateTimeParseException e) {
            throw ApiException.conflict("EDIT_NOT_UNDOABLE", "The earlier start time can't be read");
        }
    }
}
