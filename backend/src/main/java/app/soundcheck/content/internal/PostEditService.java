package app.soundcheck.content.internal;

import app.soundcheck.content.Post;
import app.soundcheck.shared.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changing a post after it was created: clips joining their slots as uploads complete, clips
 * added or removed, the author's edit, and what a tagged person does with their tag. Every
 * change locks the post's row first, so two changes to one post happen one after the other.
 */
@Service
@Transactional
public class PostEditService {

    /** The author's edit; a null field is left as it is. */
    public record PatchPost(
            @Nullable String caption,
            @Nullable UUID gigId,
            @Nullable LocalDate day,
            @Nullable List<UUID> artistIds,
            @Nullable List<UUID> taggedUserIds,
            @Nullable List<ClipPlacement> clips) {}

    /** One clip in the edited order, with its act (null: no act). */
    public record ClipPlacement(UUID id, @Nullable UUID artistId) {}

    private final PostRepository posts;
    private final ClipRepository clips;
    private final TagRepository tags;
    private final PostRules rules;
    private final PostService service;
    private final Clock clock;

    PostEditService(
            PostRepository posts,
            ClipRepository clips,
            TagRepository tags,
            PostRules rules,
            PostService service,
            Clock clock) {
        this.posts = posts;
        this.clips = clips;
        this.tags = tags;
        this.rules = rules;
        this.service = service;
        this.clock = clock;
    }

    // ── clips ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Fills the owner's slot: with its clip once the upload is complete, and/or with its preview frame.
     * Attaching the clip the slot already has changes nothing (a retry).
     */
    public Post attach(UUID actorId, UUID postId, UUID clipId, @Nullable UUID mediaId, @Nullable UUID previewMediaId) {
        PostRow post = lockOpen(postId);
        ClipRow clip = clips.find(clipId)
                .filter(c -> c.postId().equals(postId) && c.ownerId().equals(actorId))
                .orElseThrow(() -> ApiException.notFound("CLIP_NOT_FOUND", "No such clip in this post"));
        if (previewMediaId != null) {
            rules.checkNewClips(actorId, List.of(new NewClip(null, null, previewMediaId)));
            clips.setPreview(clipId, previewMediaId);
        }
        if (mediaId != null && !mediaId.equals(clip.mediaId())) {
            if (clip.mediaId() != null) {
                throw ApiException.conflict("CLIP_FILLED", "This clip already has its file");
            }
            rules.checkMedia(actorId, mediaId);
            try {
                if (!clips.attach(clipId, mediaId)) {
                    throw ApiException.conflict("CLIP_FILLED", "This clip already has its file");
                }
            } catch (DataIntegrityViolationException e) {
                throw PostRules.alreadyPosted();
            }
            rules.settle(post, false);
        }
        return view(actorId, postId);
    }

    /**
     * Adds clips to a post: its author's, or a tagged person's (collaborating puts the post on their
     * profile).
     */
    public Post addClips(UUID actorId, UUID postId, List<NewClip> added) {
        PostRow post = lockOpen(postId);
        boolean author = post.authorId().equals(actorId);
        Optional<TagRepository.Tag> tag = tags.find(postId, actorId).filter(TagRepository.Tag::shown);
        if (!author && tag.isEmpty()) {
            throw PostService.notFound();
        }
        rules.checkNewClips(actorId, added);
        added = rules.withSoleAct(post.gigId(), null, added);
        rules.checkRoom(clips.of(postId), actorId, added);
        Instant now = Instant.now(clock);
        try {
            rules.addClips(postId, actorId, added, now);
        } catch (DataIntegrityViolationException e) {
            throw PostRules.alreadyPosted();
        }
        posts.addArtists(
                postId,
                added.stream().map(NewClip::artistId).filter(Objects::nonNull).toList());
        if (!author) {
            tags.setStatus(postId, actorId, "ON_PROFILE", now);
        }
        rules.settle(post, false);
        return view(actorId, postId);
    }

    /** Removes a clip: its owner, or the post's author, may; never the post's last clip (delete the post). */
    public void removeClip(UUID actorId, UUID postId, UUID clipId) {
        PostRow post = lockOpen(postId);
        ClipRow clip = clips.find(clipId)
                .filter(c -> c.postId().equals(postId))
                .filter(c -> c.ownerId().equals(actorId) || post.authorId().equals(actorId))
                .orElseThrow(() -> ApiException.notFound("CLIP_NOT_FOUND", "No such clip in this post"));
        if (clips.of(postId).size() == 1) {
            throw ApiException.conflict("LAST_CLIP", "A post keeps at least one clip; delete the post instead");
        }
        clips.delete(clip.id());
        syncArtists(postId);
        afterClipsLeft(post, List.of(clip.ownerId()));
    }

    // ── the author's edit ────────────────────────────────────────────────────────────────────────

    public Post patch(UUID authorId, UUID postId, PatchPost edit) {
        PostRow post = lockOpen(postId);
        if (!post.authorId().equals(authorId)) {
            throw PostService.notFound();
        }
        Instant now = Instant.now(clock);
        if (edit.caption() != null) {
            posts.setCaption(
                    postId, edit.caption().isBlank() ? null : edit.caption().strip(), now);
        }
        if (edit.artistIds() != null) {
            Set<UUID> acts =
                    new LinkedHashSet<>(rules.postArtists(authorId, post.gigId(), edit.artistIds(), List.of()));
            clips.of(postId).stream()
                    .map(ClipRow::artistId)
                    .filter(Objects::nonNull)
                    .forEach(acts::add);
            posts.replaceArtists(postId, acts);
        }
        if (edit.clips() != null) {
            reorder(post, edit.clips());
            syncArtists(postId);
        }
        if (edit.taggedUserIds() != null) {
            retag(post, edit.taggedUserIds(), now);
        }
        if (edit.gigId() != null || edit.day() != null) {
            move(post, edit.gigId() == null ? post.gigId() : edit.gigId(), edit.day(), now);
        }
        return view(authorId, postId);
    }

    /**
     * A gig post's acts are its clips' acts. A post whose clips all lack an act keeps the
     * tags it has until its author picks the acts; a post not from a gig keeps the acts it was given.
     */
    private void syncArtists(UUID postId) {
        if (posts.find(postId).map(PostRow::gigId).isEmpty()) {
            return;
        }
        Set<UUID> acts = new LinkedHashSet<>();
        clips.of(postId).stream()
                .map(ClipRow::artistId)
                .filter(Objects::nonNull)
                .forEach(acts::add);
        if (!acts.isEmpty()) {
            posts.replaceArtists(postId, acts);
        }
    }

    /** The clips in the given order and acts; clips left out are removed. */
    private void reorder(PostRow post, List<ClipPlacement> placements) {
        List<ClipRow> current = clips.of(post.id());
        Set<UUID> ids = current.stream().map(ClipRow::id).collect(Collectors.toSet());
        Set<UUID> kept = new HashSet<>();
        for (ClipPlacement placement : placements) {
            if (!ids.contains(placement.id()) || !kept.add(placement.id())) {
                throw ApiException.badRequest("CLIPS_INVALID", "clips must list this post's clips, each once");
            }
        }
        if (kept.isEmpty()) {
            throw ApiException.conflict("LAST_CLIP", "A post keeps at least one clip; delete the post instead");
        }
        rules.checkArtists(
                post.authorId(),
                placements.stream()
                        .map(ClipPlacement::artistId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());
        for (int i = 0; i < placements.size(); i++) {
            clips.place(placements.get(i).id(), i, placements.get(i).artistId());
        }
        List<UUID> gone = new ArrayList<>();
        for (ClipRow clip : current) {
            if (!kept.contains(clip.id())) {
                clips.delete(clip.id());
                gone.add(clip.ownerId());
            }
        }
        posts.addArtists(
                post.id(),
                placements.stream()
                        .map(ClipPlacement::artistId)
                        .filter(Objects::nonNull)
                        .toList());
        checkPerActLimits(post.id());
        afterClipsLeft(post, gone);
    }

    private void checkPerActLimits(UUID postId) {
        Map<String, Long> perOwnerAndAct = clips.of(postId).stream()
                .collect(Collectors.groupingBy(c -> c.ownerId() + "/" + c.artistId(), Collectors.counting()));
        if (perOwnerAndAct.values().stream().anyMatch(n -> n > PostRules.MAX_CLIPS_PER_ACT)) {
            throw ApiException.badRequest(
                    "TOO_MANY_CLIPS",
                    "A post can hold at most " + PostRules.MAX_CLIPS_PER_ACT + " of one person's clips of one act");
        }
    }

    /** The tagged people become exactly these (someone who took their tag off stays off). */
    private void retag(PostRow post, List<UUID> requested, Instant now) {
        List<UUID> wanted = rules.checkTaggable(post.authorId(), requested);
        List<TagRepository.Tag> current = tags.of(List.of(post.id())).getOrDefault(post.id(), List.of());
        for (TagRepository.Tag tag : current) {
            if (tag.shown() && !wanted.contains(tag.userId())) {
                tags.untag(post.id(), tag.userId());
            }
        }
        List<UUID> added = new ArrayList<>();
        for (UUID userId : wanted) {
            if (tags.tag(post.id(), userId, now)) {
                added.add(userId);
            }
        }
        if (post.published()) {
            rules.announceTags(post.id(), post.authorId(), added);
        }
    }

    /**
     * The post moves to another gig or day: the Went marks its clips made move with it,
     * and the author's other open post there, if any, refuses the move.
     */
    private void move(PostRow post, @Nullable UUID gigId, @Nullable LocalDate requestedDay, Instant now) {
        LocalDate day = rules.dayFor(gigId, requestedDay);
        if (Objects.equals(gigId, post.gigId()) && Objects.equals(day, post.day())) {
            return;
        }
        if (gigId != null && day != null) {
            posts.lockOpen(post.authorId(), gigId, day)
                    .filter(other -> !other.id().equals(post.id()))
                    .ifPresent(other -> {
                        throw ApiException.conflict(
                                "POST_EXISTS", "You already have a post from that gig and day: add these clips to it");
                    });
        }
        posts.setGig(post.id(), gigId, day, now);
        // The clips keep their acts; the post's acts stay its clips' (no retagging with the new bill).
        syncArtists(post.id());
        List<UUID> owners =
                clips.of(post.id()).stream().map(ClipRow::ownerId).distinct().toList();
        PostRow moved = posts.find(post.id()).orElseThrow();
        rules.settle(moved, false);
        rules.clipsGone(owners, post.gigId());
    }

    // ── a tagged person ──────────────────────────────────────────────────────────────────────────

    /** Takes the viewer's tag off, with their clips; the author cannot tag them on this post again. */
    public void leave(UUID userId, UUID postId) {
        PostRow post = lockOpen(postId);
        tags.find(postId, userId).filter(TagRepository.Tag::shown).orElseThrow(PostService::notFound);
        List<ClipRow> mine = clips.of(postId).stream()
                .filter(c -> c.ownerId().equals(userId))
                .toList();
        if (!mine.isEmpty() && mine.size() == clips.of(postId).size()) {
            throw ApiException.conflict(
                    "LAST_CLIP", "Every clip left in this post is yours: ask its author to delete it");
        }
        tags.setStatus(postId, userId, "REMOVED", Instant.now(clock));
        mine.forEach(clip -> clips.delete(clip.id()));
        afterClipsLeft(post, mine.stream().map(ClipRow::ownerId).toList());
    }

    /** Puts the post on the tagged viewer's profile, or takes it off (their clips stay). */
    public Post setOnProfile(UUID userId, UUID postId, boolean onProfile) {
        lockOpen(postId);
        tags.find(postId, userId).filter(TagRepository.Tag::shown).orElseThrow(PostService::notFound);
        tags.setStatus(postId, userId, onProfile ? "ON_PROFILE" : "TAGGED", Instant.now(clock));
        return view(userId, postId);
    }

    // ── shared ───────────────────────────────────────────────────────────────────────────────────

    private void afterClipsLeft(PostRow post, List<UUID> owners) {
        rules.settle(post, false);
        rules.clipsGone(owners, post.gigId());
    }

    /** The post, locked, while it is still open (pending or live); anything else does not exist. */
    private PostRow lockOpen(UUID postId) {
        return posts.lock(postId).filter(PostRow::open).orElseThrow(PostService::notFound);
    }

    private Post view(UUID viewerId, UUID postId) {
        return service.view(viewerId, posts.find(postId).orElseThrow(PostService::notFound));
    }
}
