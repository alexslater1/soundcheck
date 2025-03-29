import { completeUpload, createUpload, getMedia } from '@/api/generated/media/media';
import type { CompleteRequest, CompleteResponse } from '@/api/generated/model';
import { createPost } from '@/api/generated/posts/posts';

import { discardFile } from './compress';
import { transport } from './transport';
import { ensurePost } from './postDraft';
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
 * Runs the upload machine for one queue entry, wired to the real API, the platform's transport and the
 * persisted store. Resolves when the entry has settled (or polling has given up); never rejects.
 */
export async function startUpload(localId: string): Promise<void> {
  const find = (): UploadEntry | undefined =>
    useUploadQueue.getState().entries.find((entry) => entry.localId === localId);
  if (running.has(localId) || !find()) {
    return;
  }
  running.add(localId);
  try {
    await runUpload({
      api: {
        createUpload: (body, idempotencyKey) =>
          createUpload(body, { headers: { 'Idempotency-Key': idempotencyKey } }),
        completeUpload: (mediaId, parts) =>
          completeWithOptionalParts(mediaId, parts ? { parts } : undefined),
        getMedia: (mediaId) => getMedia(mediaId),
      },
      transport,
      current: () => {
        const entry = find();
        if (!entry) {
          throw new Error('The upload was removed.');
        }
        return entry;
      },
      // An entry removed while it runs is simply not written back.
      update: (patch) => useUploadQueue.getState().patch(localId, patch),
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

/** Removes an upload from the list, and its temporary file with it. */
export function removeUpload(localId: string): void {
  const entry = useUploadQueue.getState().entries.find((e) => e.localId === localId);
  if (entry?.temporary) {
    discardFile(entry.fileUri);
  }
  useUploadQueue.getState().remove(localId);
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
