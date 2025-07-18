import { MMKV } from 'react-native-mmkv';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

import type { ClientMeta, PickedVideo, UploadEntry, UploadState } from './types';
import { newUuid } from './uuid';

/** States in which an upload was in the middle of something when the app last stopped. */
const IN_FLIGHT: UploadState[] = ['PREPARING', 'QUEUED', 'REQUESTING', 'UPLOADING', 'COMPLETING'];

type UploadQueueState = {
  /** Newest first. Entries of every account that used this device; screens filter by owner. */
  entries: (UploadEntry & { ownerId: string })[];
  /** `prepared` false: the queue compresses it first if it should (PREPARING). */
  enqueue: (
    ownerId: string,
    video: PickedVideo,
    clientMeta: ClientMeta,
    temporary?: boolean,
    prepared?: boolean,
  ) => string;
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
 * An entry as an older version stored it, with today's fields. In older versions a clip was a post
 * of its own: its draft becomes a group of one named by its old post key (so a retry is the same
 * request key), and a clip already posted counts as in its slot.
 */
export function upgraded<T extends UploadEntry>(stored: T): T {
  const entry = stored as T & Partial<UploadEntry>;
  const draft = entry.draft as (Partial<NonNullable<UploadEntry['draft']>> | null) | undefined;
  return {
    ...entry,
    prepared: entry.prepared ?? true,
    previewUri: entry.previewUri ?? entry.fileUri,
    clipId: entry.clipId ?? null,
    attached: entry.attached ?? entry.postId != null,
    artistId: entry.artistId ?? null,
    previewMediaId: entry.previewMediaId ?? null,
    previewState: entry.previewState ?? 'NONE',
    previewSent: entry.previewSent ?? false,
    draft: draft
      ? {
          groupKey: draft.groupKey ?? entry.postIdempotencyKey,
          order: draft.order ?? 0,
          caption: draft.caption ?? '',
          gigId: draft.gigId ?? null,
          day: draft.day ?? null,
          artistIds: draft.artistIds ?? [],
          taggedUserIds: draft.taggedUserIds ?? [],
          joinPostId: draft.joinPostId ?? null,
          request: draft.request ?? null,
        }
      : null,
  };
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
      enqueue: (ownerId, video, clientMeta, temporary = false, prepared = true) => {
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
          temporary,
          prepared,
          previewUri: video.uri,
          state: prepared ? 'QUEUED' : 'PREPARING',
          progress: 0,
          mediaId: null,
          strategy: null,
          parts: {},
          error: null,
          draft: null,
          postIdempotencyKey: newUuid(),
          postId: null,
          clipId: null,
          attached: false,
          artistId: null,
          previewMediaId: null,
          previewState: 'NONE',
          previewSent: false,
          postError: null,
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
      // 2: `prepared` and `previewUri`. Entries stored by version 1 were compressed
      // before they were queued, so they count as prepared, and their file is their preview.
      // 3: posts of several clips: see `upgraded`.
      version: 3,
      migrate: (persisted) => persisted as { entries: UploadQueueState['entries'] },
      storage,
      partialize: (state) => ({ entries: state.entries }),
      merge: (persisted, current) => ({
        ...current,
        entries: afterRestart(
          ((persisted as { entries?: UploadQueueState['entries'] })?.entries ?? []).map(upgraded),
        ),
      }),
    },
  ),
);
