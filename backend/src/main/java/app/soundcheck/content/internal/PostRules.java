package app.soundcheck.content.internal;

import app.soundcheck.catalog.Catalog;
import app.soundcheck.catalog.CatalogViews;
import app.soundcheck.catalog.PostAttendance;
import app.soundcheck.content.PostFailed;
import app.soundcheck.content.PostPublished;
import app.soundcheck.content.PostTagged;
import app.soundcheck.media.MediaInfo;
import app.soundcheck.media.MediaLibrary;
import app.soundcheck.shared.Ids;
import app.soundcheck.shared.media.PublicImages;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.social.SocialGraph;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rules creating and editing a post share: what may go into it, how many clips it may hold,
 * and what follows from a change to its clips: it goes live with its first playable clip (marking each
 * clip's owner Went), and it fails only when no clip is left that could still play. Always
 * called inside the caller's transaction, with the post's row locked.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class PostRules {

    /** Clips one person may put in a post per act; clips with no act are one more group. */
    static final int MAX_CLIPS_PER_ACT = 10;

    /** Slots in a post, all people and acts together. */
    static final int MAX_CLIPS = 60;

    static final int MAX_ARTISTS = 10;
    static final int MAX_TAGGED = 10;

    /** A gig that looks multi-day (a festival) may have posts for its later days, up to this many after it. */
    static final int MAX_DAYS_AFTER = 3;

    /** Gig days are UK calendar days. */
    static final ZoneId UK = ZoneId.of("Europe/London");

    private final PostRepository posts;
    private final ClipRepository clips;
    private final TagRepository tags;
    private final MediaLibrary media;
    private final PublicImages images;
    private final Catalog catalog;
    private final CatalogViews catalogViews;
    private final PostAttendance attendance;
    private final SocialGraph social;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PostRules(
            PostRepository posts,
            ClipRepository clips,
            TagRepository tags,
            MediaLibrary media,
            PublicImages images,
            Catalog catalog,
            CatalogViews catalogViews,
            PostAttendance attendance,
            SocialGraph social,
            ApplicationEventPublisher events,
            Clock clock) {
        this.posts = posts;
        this.clips = clips;
        this.tags = tags;
        this.media = media;
        this.images = images;
        this.catalog = catalog;
        this.catalogViews = catalogViews;
        this.attendance = attendance;
        this.social = social;
        this.events = events;
        this.clock = clock;
    }

    // ── what may go in ───────────────────────────────────────────────────────────────────────────

    /**
     * Checks clips the owner announces: each media (when given) is a video or a photo of
     * theirs whose upload is complete, that can still be shown and is in no post yet; each preview is a
     * ready image of theirs; each act exists.
     */
    void checkNewClips(UUID ownerId, List<NewClip> requested) {
        if (requested.isEmpty()) {
            throw ApiException.badRequest("CLIPS_REQUIRED", "A post needs at least one clip");
        }
        Set<UUID> mediaIds = new HashSet<>();
        for (NewClip clip : requested) {
            if (clip.mediaId() != null) {
                if (!mediaIds.add(clip.mediaId())) {
                    throw ApiException.badRequest("MEDIA_REPEATED", "The same clip is in the request twice");
                }
                checkMedia(ownerId, clip.mediaId());
            }
            if (clip.previewMediaId() != null && !images.readyImageOwnedBy(clip.previewMediaId(), ownerId)) {
                throw ApiException.badRequest("PREVIEW_INVALID", "previewMediaId must be an image you uploaded");
            }
        }
        checkArtists(
                ownerId,
                requested.stream()
                        .map(NewClip::artistId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());
    }

    /**
     * The clip may be attached to a slot of the owner's. A slot holds a video or a
     * photo: an image is READY once its upload is complete, so a photo makes its post live at once.
     */
    MediaInfo checkMedia(UUID ownerId, UUID mediaId) {
        // One answer for "no such clip" and "not yours": ids cannot be probed.
        MediaInfo clip = media.find(mediaId)
                .filter(m -> m.ownerId().equals(ownerId))
                .orElseThrow(() ->
                        ApiException.badRequest("MEDIA_INVALID", "mediaId must be a video or photo you uploaded"));
        if (clip.status().equals("AWAITING_UPLOAD")) {
            throw ApiException.conflict("MEDIA_NOT_UPLOADED", "Complete the upload before posting it");
        }
        if (clip.unusable()) {
            throw ApiException.conflict("MEDIA_NOT_USABLE", "This clip could not be processed");
        }
        if (clips.findByMedia(mediaId).isPresent()) {
            throw alreadyPosted();
        }
        return clip;
    }

    void checkArtists(UUID viewerId, Collection<UUID> artistIds) {
        var known = catalogViews.artists(viewerId, artistIds).keySet();
        for (UUID id : artistIds) {
            if (!known.contains(id)) {
                throw ApiException.badRequest("ARTIST_UNKNOWN", "Unknown artist " + id);
            }
        }
    }

    /**
     * The post's acts: every act a clip is of, plus any given ones. Leaving the field out no longer tags
     * the gig's whole bill: a clip of one act would then show on every act's page.
     */
    List<UUID> postArtists(
            UUID viewerId, @Nullable UUID gigId, @Nullable List<UUID> artistIds, Collection<NewClip> newClips) {
        Set<UUID> result = new LinkedHashSet<>();
        if (artistIds != null) {
            List<UUID> requested = List.copyOf(new LinkedHashSet<>(artistIds));
            if (requested.size() > MAX_ARTISTS) {
                throw ApiException.badRequest("TOO_MANY_ARTISTS", "A post can tag at most " + MAX_ARTISTS + " artists");
            }
            checkArtists(viewerId, requested);
            result.addAll(requested);
        }
        newClips.stream().map(NewClip::artistId).filter(Objects::nonNull).forEach(result::add);
        return List.copyOf(result);
    }

    /**
     * The UK day a post from the gig is for: the gig's own day, or (a festival) one of the next
     * {@value #MAX_DAYS_AFTER}; never a day still to come. Null for a post not from a gig.
     */
    @Nullable
    LocalDate dayFor(@Nullable UUID gigId, @Nullable LocalDate requested) {
        if (gigId == null) {
            return null;
        }
        LocalDate gigDay =
                catalog.gigDay(gigId).orElseThrow(() -> ApiException.badRequest("GIG_UNKNOWN", "Unknown gig " + gigId));
        LocalDate today = LocalDate.now(clock.withZone(UK));
        // A clip is filmed at a gig that has happened or is on today: a later one is a mistake.
        if (gigDay.isAfter(today)) {
            throw ApiException.badRequest(
                    "GIG_NOT_HAPPENED", "Clips can be posted only from a gig that has happened or is on today");
        }
        // A cancelled gig did not happen.
        if (catalog.gigCancelled(gigId)) {
            throw ApiException.badRequest("GIG_CANCELLED", "This gig was cancelled: there are no clips from it");
        }
        LocalDate day = requested == null ? gigDay : requested;
        if (day.isBefore(gigDay) || day.isAfter(gigDay.plusDays(MAX_DAYS_AFTER)) || day.isAfter(today)) {
            throw ApiException.badRequest(
                    "DAY_INVALID",
                    "day must be the gig's day, or one of the " + MAX_DAYS_AFTER + " after it, up to today");
        }
        return day;
    }

    /**
     * A clip with no act takes the post's one act, when there is exactly one: the single
     * act given in {@code artistIds}, or, when the field is left out, a single-act gig's act (V105 did the
     * same for older clips). It is then on that act's page. Otherwise the clip waits for its author.
     */
    List<NewClip> withSoleAct(@Nullable UUID gigId, @Nullable List<UUID> artistIds, List<NewClip> newClips) {
        if (newClips.stream().allMatch(clip -> clip.artistId() != null)) {
            return newClips;
        }
        List<UUID> candidates = artistIds != null
                ? List.copyOf(new LinkedHashSet<>(artistIds))
                : gigId == null ? List.of() : catalogViews.lineup(gigId);
        if (candidates.size() != 1) {
            return newClips;
        }
        UUID sole = candidates.getFirst();
        return newClips.stream()
                .map(clip -> clip.artistId() == null ? new NewClip(clip.mediaId(), sole, clip.previewMediaId()) : clip)
                .toList();
    }

    /** People the author may tag: up to {@value #MAX_TAGGED}, each someone they follow, with no block. */
    List<UUID> checkTaggable(UUID authorId, @Nullable List<UUID> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(requested));
        if (ids.size() > MAX_TAGGED) {
            throw ApiException.badRequest("TOO_MANY_TAGGED", "A post can tag at most " + MAX_TAGGED + " people");
        }
        Set<UUID> followed = social.followedAmong(authorId, ids);
        Set<UUID> blocked = social.blockedAmong(authorId, ids);
        for (UUID id : ids) {
            if (id.equals(authorId) || !followed.contains(id) || blocked.contains(id)) {
                throw ApiException.badRequest("TAG_NOT_ALLOWED", "You can tag only people you follow");
            }
        }
        return ids;
    }

    /** The post may hold its clips plus these new ones of the owner's. */
    void checkRoom(List<ClipRow> existing, UUID ownerId, List<NewClip> added) {
        if (existing.size() + added.size() > MAX_CLIPS) {
            throw ApiException.badRequest("TOO_MANY_CLIPS", "A post can hold at most " + MAX_CLIPS + " clips");
        }
        Map<Optional<UUID>, Integer> perAct = new HashMap<>();
        existing.stream()
                .filter(clip -> clip.ownerId().equals(ownerId))
                .forEach(clip -> perAct.merge(Optional.ofNullable(clip.artistId()), 1, Integer::sum));
        for (NewClip clip : added) {
            if (perAct.merge(Optional.ofNullable(clip.artistId()), 1, Integer::sum) > MAX_CLIPS_PER_ACT) {
                throw ApiException.badRequest(
                        "TOO_MANY_CLIPS", "A post can hold at most " + MAX_CLIPS_PER_ACT + " of your clips of one act");
            }
        }
    }

    /** Adds the owner's clips after the post's last one, in the order given; returns the new rows. */
    List<ClipRow> addClips(UUID postId, UUID ownerId, List<NewClip> added, Instant now) {
        int position = clips.lastPosition(postId);
        List<ClipRow> rows = new ArrayList<>();
        for (NewClip clip : added) {
            ClipRow row = new ClipRow(
                    Ids.newId(),
                    postId,
                    ownerId,
                    ++position,
                    clip.artistId(),
                    clip.mediaId(),
                    clip.previewMediaId(),
                    now);
            clips.insert(row);
            rows.add(row);
        }
        return rows;
    }

    // ── what follows from a change ───────────────────────────────────────────────────────────────

    /**
     * After the post's clips changed: a pending post with a playable clip is published, one with no clip
     * left that could still play is failed; a live post marks the owners of its playable clips Went.
     *
     * @param afterProcessing whether this follows a clip being transcoded (the author is told it is ready)
     * @return true when the post's status changed
     */
    boolean settle(PostRow post, boolean afterProcessing) {
        if (!post.status().equals("PENDING_MEDIA") && !post.published()) {
            return false;
        }
        List<ClipRow> slots = clips.of(post.id());
        Map<UUID, MediaInfo> infos = media.findAll(
                slots.stream().map(ClipRow::mediaId).filter(Objects::nonNull).toList());
        boolean playable = slots.stream()
                .anyMatch(clip -> clip.mediaId() != null
                        && infos.containsKey(clip.mediaId())
                        && infos.get(clip.mediaId()).ready());
        Instant now = Instant.now(clock);
        if (post.published()) {
            wentLive(post, slots, infos);
            return false;
        }
        if (playable) {
            if (!posts.publish(post.id(), now)) {
                return false;
            }
            wentLive(post, slots, infos);
            events.publishEvent(new PostPublished(post.id(), post.authorId(), now, afterProcessing));
            announceTags(
                    post.id(),
                    post.authorId(),
                    tags.of(List.of(post.id())).getOrDefault(post.id(), List.of()).stream()
                            .filter(TagRepository.Tag::shown)
                            .map(TagRepository.Tag::userId)
                            .toList());
            return true;
        }
        boolean couldStillPlay = slots.stream()
                .anyMatch(clip -> clip.mediaId() == null
                        || (infos.containsKey(clip.mediaId())
                                && !infos.get(clip.mediaId()).unusable()));
        if (!couldStillPlay && posts.fail(post.id(), now)) {
            events.publishEvent(new PostFailed(post.id(), post.authorId()));
            return true;
        }
        return false;
    }

    /**
     * Each owner of a playable clip in the live post went to its gig, in the same
     * transaction, so a live clip and the WENT it made are never seen apart. Safe to repeat.
     */
    private void wentLive(PostRow post, List<ClipRow> slots, Map<UUID, MediaInfo> infos) {
        UUID gigId = post.gigId();
        if (gigId == null) {
            return;
        }
        Map<UUID, Set<UUID>> actsByOwner = new LinkedHashMap<>();
        slots.stream()
                .filter(clip -> clip.mediaId() != null
                        && infos.containsKey(clip.mediaId())
                        && infos.get(clip.mediaId()).ready())
                .forEach(clip -> {
                    Set<UUID> acts = actsByOwner.computeIfAbsent(clip.ownerId(), owner -> new LinkedHashSet<>());
                    if (clip.artistId() != null) {
                        acts.add(clip.artistId());
                    }
                });
        actsByOwner.forEach((owner, acts) -> attendance.postWentLive(owner, gigId, acts));
    }

    /** The users' clips left the gig's live posts: a WENT only posts made goes with the last of them. */
    void clipsGone(Collection<UUID> ownerIds, @Nullable UUID gigId) {
        if (gigId == null) {
            return;
        }
        for (UUID owner : new LinkedHashSet<>(ownerIds)) {
            if (!posts.hasLivePost(owner, gigId)) {
                attendance.lastPostGone(owner, gigId);
            }
        }
    }

    /** Tells newly tagged people about a live post (POST_TAGGED). */
    void announceTags(UUID postId, UUID authorId, List<UUID> userIds) {
        if (!userIds.isEmpty()) {
            events.publishEvent(new PostTagged(postId, authorId, List.copyOf(userIds)));
        }
    }

    static ApiException alreadyPosted() {
        return ApiException.conflict("MEDIA_ALREADY_POSTED", "This clip has already been posted");
    }
}
