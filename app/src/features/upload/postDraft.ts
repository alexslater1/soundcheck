import type { Post } from '@/api/generated/model';
import { ApiError } from '@/api/http';

import type { PostRequest, UploadEntry } from './types';

export type GroupDeps = {
  /** The entries of one post (one `draft.groupKey`), as they are now. */
  entries: () => UploadEntry[];
  /** Who is posting: their slots are the ones this device fills. */
  ownerId: string;
  update: (localId: string, patch: Partial<UploadEntry>) => void;
  /** `POST /posts` with the group's key as `Idempotency-Key`. */
  createPost: (body: PostRequest, idempotencyKey: string) => Promise<Post>;
  /** `POST /posts/{id}/clips`: the clips join an existing post. */
  addPostClips: (postId: string, body: PostRequest) => Promise<Post>;
  /** `PATCH /posts/{id}/clips/{clipId}`: a slot gets its clip and/or its preview. */
  fillPostClip: (
    postId: string,
    clipId: string,
    body: { mediaId?: string; previewMediaId?: string },
  ) => Promise<Post>;
  /** `DELETE /posts/{id}/clips/{clipId}`: a slot whose clip can never come. */
  removePostClip: (postId: string, clipId: string) => Promise<void>;
};

/** The server has the clip's bytes: it can go into its slot. */
export function accepted(entry: UploadEntry): boolean {
  return entry.mediaId !== null && (entry.state === 'PROCESSING' || entry.state === 'READY');
}

/** The group's entries in the order their clips were announced. */
function ordered(entries: UploadEntry[]): UploadEntry[] {
  return entries
    .filter((entry) => entry.draft !== null)
    .sort((a, b) => (a.draft?.order ?? 0) - (b.draft?.order ?? 0));
}

/** The body for the group's first request: each clip with what is there already. */
export function requestFor(entries: UploadEntry[]): PostRequest {
  const head = entries[0]!.draft!;
  const clips = entries.map((entry) => ({
    mediaId: accepted(entry) ? entry.mediaId : null,
    artistId: entry.artistId,
    song: entry.song,
    previewMediaId: entry.previewState === 'READY' ? entry.previewMediaId : null,
  }));
  if (head.joinPostId) {
    return { clips };
  }
  return {
    clips,
    caption: head.caption.trim() === '' ? null : head.caption.trim(),
    gigId: head.gigId,
    day: head.day,
    artistIds: head.artistIds,
    taggedUserIds: head.taggedUserIds,
  };
}

/**
 * The slots a request made for this device's clips, in the order they were announced: the last
 * `count` slots of the owner's by position. The server gives new slots the positions after the post's
 * last one, in the order of the request, inside one locked transaction, so they are the owner's newest.
 * (A retry answered from the idempotency record after more clips were added would pick newer slots; the
 * app does not add clips to a post while its first request is unanswered.)
 */
export function newSlots(post: Post, ownerId: string, count: number): string[] {
  const mine = post.clips
    .filter((clip) => clip.owner.id === ownerId)
    .sort((a, b) => a.position - b.position);
  return mine.slice(Math.max(0, mine.length - count)).map((clip) => clip.id);
}

function message(error: unknown): string {
  return error instanceof Error && error.message ? error.message : 'Could not post.';
}

/**
 * Brings one post in step with its uploads. The first call after Post creates the post (or
 * adds the clips to an existing one) with a slot per clip, sending each clip's video and preview if
 * they are there already; later calls fill each slot as its upload completes or its preview is made,
 * and give up a slot whose clip was refused. Safe to repeat: the create is idempotent by the group's
 * key with the very same body, and a slot is filled once.
 */
export async function syncGroup(deps: GroupDeps): Promise<void> {
  const group = ordered(deps.entries());
  if (group.length === 0) {
    return;
  }
  const head = group[0]!.draft!;
  if (!group.some((entry) => entry.postId)) {
    const request = head.request ?? requestFor(group);
    if (!head.request) {
      group.forEach((entry) => deps.update(entry.localId, { draft: { ...entry.draft!, request } }));
    }
    let post: Post;
    try {
      post = head.joinPostId
        ? await deps.addPostClips(head.joinPostId, request)
        : await deps.createPost(request, head.groupKey);
    } catch (error) {
      group.forEach((entry) => deps.update(entry.localId, { postError: message(error) }));
      return;
    }
    const slots = newSlots(post, deps.ownerId, group.length);
    if (slots.length < group.length) {
      group.forEach((entry) =>
        deps.update(entry.localId, { postError: 'The post did not keep every clip.' }),
      );
      return;
    }
    group.forEach((entry, i) =>
      deps.update(entry.localId, {
        postId: post.id,
        clipId: slots[i]!,
        attached: request.clips[i]?.mediaId != null,
        previewSent: request.clips[i]?.previewMediaId != null,
        postError: null,
      }),
    );
  }

  for (const { localId } of group) {
    const entry = deps.entries().find((e) => e.localId === localId);
    if (!entry?.postId || !entry.clipId) {
      continue;
    }
    // A clip the server refused can never fill its slot: the slot goes, rather than waiting a day.
    if (entry.state === 'REJECTED' && !entry.attached) {
      try {
        await deps.removePostClip(entry.postId, entry.clipId);
        deps.update(localId, { clipId: null });
      } catch {
        // The last clip of the post (or the server unreachable): it stays until the post is deleted.
      }
      continue;
    }
    const body: { mediaId?: string; previewMediaId?: string } = {};
    if (!entry.attached && accepted(entry)) {
      body.mediaId = entry.mediaId!;
    }
    if (
      !entry.previewSent &&
      !entry.attached &&
      entry.previewState === 'READY' &&
      entry.previewMediaId
    ) {
      body.previewMediaId = entry.previewMediaId;
    }
    if (body.mediaId === undefined && body.previewMediaId === undefined) {
      continue;
    }
    try {
      await deps.fillPostClip(entry.postId, entry.clipId, body);
      deps.update(localId, {
        attached: entry.attached || body.mediaId !== undefined,
        previewSent: entry.previewSent || body.previewMediaId !== undefined,
        postError: null,
      });
    } catch (error) {
      const gone = error instanceof ApiError && error.status === 404;
      deps.update(localId, {
        postError: gone ? 'The post this clip was for is gone.' : message(error),
      });
    }
  }
}

/** A chain per post, so two calls for one post (an upload completing, a preview made) never overlap. */
export function serialiser() {
  const chains = new Map<string, Promise<void>>();
  return (key: string, work: () => Promise<void>): Promise<void> => {
    const next = (chains.get(key) ?? Promise.resolve()).then(work, work);
    const settled = next.catch(() => undefined);
    chains.set(key, settled);
    void settled.then(() => {
      if (chains.get(key) === settled) {
        chains.delete(key);
      }
    });
    return settled;
  };
}
