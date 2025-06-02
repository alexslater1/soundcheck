import { Platform } from 'react-native';

import { completeUpload, createUpload, getMedia } from '@/api/generated/media/media';
import type { CompleteRequest, CompleteResponse } from '@/api/generated/model';
import { createPost, deletePost } from '@/api/generated/posts/posts';
import { ApiError } from '@/api/http';

import { compressVideo, discardFile } from './compress';
import { currentConnection } from './connection';
import { ensurePost } from './postDraft';
import { prepareClip } from './prepareClip';
import { transport } from './transport';
import type { PostDraft, UploadEntry } from './types';
import { runUpload } from './uploadMachine';
import { useUploadQueue } from './uploadQueue';

/** Entries with a machine running, so a second tap on Retry (or a screen re-mount) cannot start another. */
const running = new Set<string>();

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
        compress: compressVideo,
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
      onAccepted: () => ensurePostFor(localId),
    });
  } finally {
    running.delete(localId);
  }
  discardWhenDone(localId);
}

async function ensurePostFor(localId: string): Promise<void> {
  const find = () => useUploadQueue.getState().entries.find((e) => e.localId === localId);
  if (!find()) {
    return;
  }
  await ensurePost({
    current: () => {
      const entry = find();
      if (!entry) {
        throw new Error('The upload was removed.');
      }
      return entry;
    },
    update: (patch) => useUploadQueue.getState().patch(localId, patch),
    createPost: (body, idempotencyKey) =>
      createPost(body, { headers: { 'Idempotency-Key': idempotencyKey } }),
  });
}

/**
 * The user pressed Post: remember what they wrote, and create the post now if the upload is already
 * complete (otherwise the upload does it the moment it completes).
 */
export async function submitPost(localId: string, draft: PostDraft): Promise<void> {
  useUploadQueue.getState().patch(localId, { draft, postError: null });
  await ensurePostFor(localId);
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
 * Removes an upload from the list. An upload that has become a post is the post's clip: removing it
 * deletes the post as well (the screen asks first), so the Create list and the profile never disagree.
 * A post that is already gone (404) counts as deleted. Rejects, leaving the entry, when the server
 * could not be reached.
 */
export async function removeUpload(localId: string): Promise<void> {
  const entry = useUploadQueue.getState().entries.find((e) => e.localId === localId);
  if (entry?.postId) {
    try {
      await deletePost(entry.postId);
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 404)) {
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
  return entry.postId !== null && entry.state === 'READY';
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
 * and a post that was written but never created (the request failed, or the app was closed first) is
 * sent again; its idempotency key makes that safe.
 */
export function resumeProcessing(ownerId: string): void {
  for (const entry of useUploadQueue.getState().entries) {
    if (entry.ownerId !== ownerId) {
      continue;
    }
    if (entry.state === 'PROCESSING') {
      void startUpload(entry.localId);
    }
    if (entry.draft && !entry.postId && (entry.state === 'PROCESSING' || entry.state === 'READY')) {
      void ensurePostFor(entry.localId);
    }
  }
}
