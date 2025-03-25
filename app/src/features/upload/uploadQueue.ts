import { MMKV } from 'react-native-mmkv';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

import type { ClientMeta, PickedVideo, UploadEntry, UploadState } from './types';
import { newUuid } from './uuid';

/** States in which an upload was in the middle of something when the app last stopped. */
const IN_FLIGHT: UploadState[] = ['QUEUED', 'REQUESTING', 'UPLOADING', 'COMPLETING'];

type UploadQueueState = {
  /** Newest first. Entries of every account that used this device; screens filter by owner. */
  entries: (UploadEntry & { ownerId: string })[];
  enqueue: (ownerId: string, video: PickedVideo, clientMeta: ClientMeta) => string;
  patch: (localId: string, patch: Partial<UploadEntry>) => void;
  remove: (localId: string) => void;
};

// MMKV: a memory-mapped key-value file on Android (synchronous, so every part's ETag is on disk before
// the next part starts); localStorage in the browser; an in-memory fake under Jest.
const mmkv = new MMKV({ id: 'soundcheck.uploads' });
const storage = createJSONStorage(() => ({
  getItem: (key) => mmkv.getString(key) ?? null,
  setItem: (key, value) => mmkv.set(key, value),
  removeItem: (key) => {
    mmkv.delete(key);
  },
}));

/** What a stored queue looks like after a restart: nothing is in flight any more. */
export function afterRestart<T extends UploadEntry>(entries: T[]): T[] {
  return entries.map((entry) =>
    IN_FLIGHT.includes(entry.state)
      ? { ...entry, state: 'FAILED', error: 'Interrupted before it finished. Retry to continue.' }
      : entry,
  );
}

/**
 * The upload queue: every upload this device has started, persisted after each change. What
 * survives a restart is enough to continue: the idempotency key (same ticket again), the ETags of
 * the parts already uploaded, and the file's location.
 */
export const useUploadQueue = create<UploadQueueState>()(
  persist(
    (set) => ({
      entries: [],
      enqueue: (ownerId, video, clientMeta) => {
        const localId = newUuid();
        const entry: UploadEntry & { ownerId: string } = {
          localId,
          ownerId,
          idempotencyKey: newUuid(),
          createdAt: Date.now(),
          fileUri: video.uri,
          fileName: video.fileName,
          contentType: video.contentType,
          bytes: video.bytes,
          durationMs: video.durationMs,
          width: video.width,
          height: video.height,
          clientMeta,
          state: 'QUEUED',
          progress: 0,
          mediaId: null,
          strategy: null,
          parts: {},
          error: null,
        };
        set((state) => ({ entries: [entry, ...state.entries] }));
        return localId;
      },
      patch: (localId, patch) =>
        set((state) => ({
          entries: state.entries.map((entry) =>
            entry.localId === localId ? { ...entry, ...patch } : entry,
          ),
        })),
      remove: (localId) =>
        set((state) => ({ entries: state.entries.filter((entry) => entry.localId !== localId) })),
    }),
    {
      name: 'upload-queue',
      version: 1,
      storage,
      partialize: (state) => ({ entries: state.entries }),
      merge: (persisted, current) => ({
        ...current,
        entries: afterRestart(
          (persisted as { entries?: UploadQueueState['entries'] })?.entries ?? [],
        ),
      }),
    },
  ),
);
