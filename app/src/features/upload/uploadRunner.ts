import { Platform } from 'react-native';

import { completeUpload, createUpload, getMedia } from '@/api/generated/media/media';
import type { CompleteRequest, CompleteResponse } from '@/api/generated/model';
import {
  addPostClips,
  createPost,
  deletePost,
  fillPostClip,
  removePostClip,
} from '@/api/generated/posts/posts';
import { ApiError } from '@/api/http';

import { compressVideo, discardFile } from './compress';
import { currentConnection } from './connection';
import { serialiser, syncGroup } from './postDraft';
import { previewFrame } from './previewFrame';
import { prepareClip } from './prepareClip';
import { oneAtATime, serially } from './serially';
import { transport } from './transport';
import type { PostRequest, UploadEntry } from './types';
import { uploadImage } from './uploadImage';
import { runUpload } from './uploadMachine';
import { useUploadQueue } from './uploadQueue';
import { newUuid } from './uuid';

/** Entries with a machine running, so a second tap on Retry (or a screen re-mount) cannot start another. */
const running = new Set<string>();

/**
 * One compression at a time: several clips picked together are prepared one after another (an encode
 * holds the phone's video encoder and a lot of memory), while the ones already prepared upload.
 */
const compressOneAtATime = serially(compressVideo);

/**
 * One upload sends bytes at a time: two clips of a post no longer double the parts in
 * flight; the second waits, QUEUED, until the first has completed.
 */
const sendOneAtATime = oneAtATime();

/**
 * `completeUpload` as it runs: a single-PUT upload sends no body (`undefined`), but orval 7 types the
 * optional body as required.
 */
const completeWithOptionalParts = completeUpload as (
  mediaId: string,
  body: CompleteRequest | undefined,
) => Promise<CompleteResponse>;

/**
 * Runs one queue entry: first the PREPARING step if it has not been done (compress or not),
 * then the upload machine, wired to the real API, the platform's transport and the persisted store.
 * Resolves when the entry has settled (or polling has given up); never rejects.
 */
export async function startUpload(localId: string): Promise<void> {
  const find = (): UploadEntry | undefined =>
    useUploadQueue.getState().entries.find((entry) => entry.localId === localId);
  if (running.has(localId) || !find()) {
    return;
  }
  if (running.size === 0) {
    // Nothing is sending, so any temporary part file is a leftover from an app killed mid-part.
    transport.discardLeftovers();
  }
  running.add(localId);
  const current = () => {
    const entry = find();
    if (!entry) {
      throw new Error('The upload was removed.');
    }
    return entry;
  };
  // An entry removed while it runs is simply not written back.
  const update = (patch: Partial<UploadEntry>) => useUploadQueue.getState().patch(localId, patch);
  try {
    if (!current().prepared) {
      const stillThere = await prepareClip({
        current,
        update,
        connection: currentConnection,
        compress: compressOneAtATime,
        discard: discardFile,
        platform: Platform.OS,
      });
      if (!stillThere) {
        return;
      }
    }
    await runUpload({
      api: {
        createUpload: (body, idempotencyKey) =>
          createUpload(body, { headers: { 'Idempotency-Key': idempotencyKey } }),
        completeUpload: (mediaId, parts) =>
          completeWithOptionalParts(mediaId, parts ? { parts } : undefined),
        getMedia: (mediaId) => getMedia(mediaId),
      },
      transport,
      current,
      update,
      sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
      random: Math.random,
      onAccepted: () => syncPostOf(localId),
      oneAtATime: sendOneAtATime,
    });
  } finally {
    running.delete(localId);
  }
  discardWhenDone(localId);
}

/** One request at a time per post: an upload completing and a preview being made never overlap. */
const perPost = serialiser();

/** Brings the post an entry belongs to in step with its uploads (see `syncGroup`). */
async function syncPostOf(localId: string): Promise<void> {
  const entry = useUploadQueue.getState().entries.find((e) => e.localId === localId);
  const groupKey = entry?.draft?.groupKey;
  if (!entry || !groupKey) {
    return;
  }
  await perPost(groupKey, () =>
    syncGroup({
      ownerId: entry.ownerId,
      entries: () =>
        useUploadQueue.getState().entries.filter((e) => e.draft?.groupKey === groupKey),
      update: (id, patch) => useUploadQueue.getState().patch(id, patch),
      createPost: (body, idempotencyKey) =>
        createPost(body, { headers: { 'Idempotency-Key': idempotencyKey } }),
      addPostClips: (postId, body: PostRequest) => addPostClips(postId, { clips: body.clips }),
      fillPostClip: (postId, clipId, body) => fillPostClip(postId, clipId, body),
      removePostClip: (postId, clipId) => removePostClip(postId, clipId),
    }),
  );
}

/** What the user chose on the Create tab for one post. */
export type PostInput = {
  caption: string;
  gigId: string | null;
  day: string | null;
  taggedUserIds: string[];
  /** Add the clips to this existing post instead of making one. */
  joinPostId: string | null;
  /** The clips in the order they are announced (running order), each with its act. */
  clips: { localId: string; artistId: string | null }[];
};

/**
 * The user pressed Post: the clips become one post. Each entry remembers the post's draft (one key for
 * the whole post), and the post is created at once, with a slot for each clip: clips still uploading
 * fill their slot when they complete. Resolves once the first request has been answered (or failed).
 */
export async function submitPost(input: PostInput): Promise<void> {
  const groupKey = newUuid();
  const artistIds = [
    ...new Set(input.clips.map((clip) => clip.artistId).filter((id): id is string => id !== null)),
  ].slice(0, 10);
  input.clips.forEach((clip, order) =>
    useUploadQueue.getState().patch(clip.localId, {
      artistId: clip.artistId,
      postError: null,
      draft: {
        groupKey,
        order,
        caption: input.caption,
        gigId: input.gigId,
        day: input.day,
        artistIds,
        taggedUserIds: input.taggedUserIds,
        joinPostId: input.joinPostId,
        request: null,
      },
    }),
  );
  const first = input.clips[0];
  if (first) {
    await syncPostOf(first.localId);
  }
}

/** "Post again" on a clip whose post request failed: the same request, with the same key. */
export async function retryPost(localId: string): Promise<void> {
  await syncPostOf(localId);
}

/**
 * Makes the clip's preview frame and uploads it as an image, then hands it to the post if the
 * clip is already in one. A preview that cannot be made or sent is simply left out.
 */
export async function makePreview(localId: string): Promise<void> {
  const find = () => useUploadQueue.getState().entries.find((e) => e.localId === localId);
  const entry = find();
  if (!entry || entry.previewState !== 'NONE') {
    return;
  }
  useUploadQueue.getState().patch(localId, { previewState: 'MAKING' });
  try {
    const frame = await previewFrame(entry.previewUri);
    if (!frame) {
      throw new Error('No frame');
    }
    const previewMediaId = await uploadImage(frame);
    useUploadQueue.getState().patch(localId, { previewMediaId, previewState: 'READY' });
  } catch {
    useUploadQueue.getState().patch(localId, { previewState: 'FAILED' });
    return;
  }
  if (find()?.draft) {
    await syncPostOf(localId);
  }
}

/** States in which the server has the bytes: the local copy is no longer needed. */
const WITH_SERVER = ['PROCESSING', 'READY', 'REJECTED'];

/** Deletes the compressed temporary file of an upload whose bytes are safely with the server. */
function discardWhenDone(localId: string): void {
  const entry = useUploadQueue.getState().entries.find((e) => e.localId === localId);
  if (entry?.temporary && WITH_SERVER.includes(entry.state)) {
    discardFile(entry.fileUri);
    useUploadQueue.getState().patch(localId, { temporary: false });
  }
}

/** Drops an entry from the queue, and its temporary file with it. */
function forget(localId: string): void {
  const entry = useUploadQueue.getState().entries.find((e) => e.localId === localId);
  if (entry?.temporary) {
    discardFile(entry.fileUri);
  }
  useUploadQueue.getState().remove(localId);
}

/**
 * Removes an upload from the list. An upload that is in a post is one of the post's clips: removing it
 * removes that clip from the post, or deletes the post when it is its last clip (the screen asks
 * first), so the Create list and the profile never disagree. A post or clip that is already gone (404)
 * counts as removed. Rejects, leaving the entry, when the server could not be reached.
 */
export async function removeUpload(localId: string): Promise<void> {
  const entry = useUploadQueue.getState().entries.find((e) => e.localId === localId);
  const gone = (error: unknown) => error instanceof ApiError && error.status === 404;
  if (entry?.postId) {
    try {
      if (entry.clipId) {
        try {
          await removePostClip(entry.postId, entry.clipId);
        } catch (error) {
          // 409: the post's last clip; the post goes with it.
          if (!(error instanceof ApiError && error.status === 409)) {
            throw error;
          }
          await deletePost(entry.postId);
        }
      } else {
        await deletePost(entry.postId);
      }
    } catch (error) {
      if (!gone(error)) {
        throw error;
      }
    }
  }
  forget(localId);
}

/** A post was deleted elsewhere (its own screen, the profile): its upload leaves the Create list. */
export function forgetPost(postId: string): void {
  for (const entry of useUploadQueue.getState().entries) {
    if (entry.postId === postId) {
      forget(entry.localId);
    }
  }
}

/**
 * Whether an upload is finished business: its post exists and the clip is playable, so the post on
 * the profile is the place to see it. Such entries leave the Create list (`pruneFinished`).
 */
export function isFinished(entry: UploadEntry): boolean {
  return entry.postId !== null && entry.attached && entry.state === 'READY';
}

/** Drops the finished uploads of one account; returns how many went. */
export function pruneFinished(ownerId: string): number {
  const done = useUploadQueue
    .getState()
    .entries.filter((entry) => entry.ownerId === ownerId && isFinished(entry));
  done.forEach((entry) => forget(entry.localId));
  return done.length;
}

/**
 * Uploads waiting on the worker pick their polling back up (after a restart, or a five-minute give-up),
 * and a post that was written but never created, or a clip not yet in its slot (the request failed, or
 * the app was closed first), is sent again; the post's key and the slot's state make that safe.
 */
export function resumeProcessing(ownerId: string): void {
  for (const entry of useUploadQueue.getState().entries) {
    if (entry.ownerId !== ownerId) {
      continue;
    }
    if (entry.state === 'PROCESSING') {
      void startUpload(entry.localId);
    }
    const unsent = !entry.postId || (!entry.attached && entry.mediaId !== null);
    if (entry.draft && unsent && entry.state !== 'FAILED') {
      void syncPostOf(entry.localId);
    }
  }
}
