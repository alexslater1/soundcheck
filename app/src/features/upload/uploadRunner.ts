import { completeUpload, createUpload, getMedia } from '@/api/generated/media/media';
import type { CompleteRequest, CompleteResponse } from '@/api/generated/model';

import { transport } from './transport';
import type { UploadEntry } from './types';
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
    });
  } finally {
    running.delete(localId);
  }
}

/** Uploads waiting on the worker pick their polling back up (after a restart, or a five-minute give-up). */
export function resumeProcessing(ownerId: string): void {
  for (const entry of useUploadQueue.getState().entries) {
    if (entry.ownerId === ownerId && entry.state === 'PROCESSING') {
      void startUpload(entry.localId);
    }
  }
}
