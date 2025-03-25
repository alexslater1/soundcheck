import type { PickedVideo, UploadEntry } from './types';
import { afterRestart, useUploadQueue } from './uploadQueue';

const video: PickedVideo = {
  uri: 'file:///clip.mp4',
  fileName: 'clip.mp4',
  contentType: 'video/mp4',
  bytes: 20_000_000,
  durationMs: 20_000,
  width: 720,
  height: 1280,
};

describe('upload queue', () => {
  beforeEach(() => useUploadQueue.setState({ entries: [] }));

  it('adds an entry with its own idempotency key, newest first', () => {
    const first = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    const second = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });

    const entries = useUploadQueue.getState().entries;
    expect(entries.map((e) => e.localId)).toEqual([second, first]);
    expect(entries[0]).toMatchObject({
      ownerId: 'user-1',
      state: 'QUEUED',
      progress: 0,
      mediaId: null,
      parts: {},
      bytes: 20_000_000,
      clientMeta: { platform: 'android' },
    });
    // Two uploads of the same file are two uploads: different keys, so the server opens two.
    expect(entries[0]!.idempotencyKey).not.toBe(entries[1]!.idempotencyKey);
  });

  it('patches and removes by local id', () => {
    const id = useUploadQueue.getState().enqueue('user-1', video, { platform: 'web' });
    const other = useUploadQueue.getState().enqueue('user-1', video, { platform: 'web' });

    useUploadQueue.getState().patch(id, { state: 'UPLOADING', parts: { 1: '"a"' }, progress: 0.4 });
    expect(useUploadQueue.getState().entries.find((e) => e.localId === id)).toMatchObject({
      state: 'UPLOADING',
      parts: { 1: '"a"' },
      progress: 0.4,
    });
    expect(useUploadQueue.getState().entries.find((e) => e.localId === other)?.state).toBe(
      'QUEUED',
    );

    useUploadQueue.getState().remove(id);
    expect(useUploadQueue.getState().entries.map((e) => e.localId)).toEqual([other]);
  });

  it('is read back from storage after a restart, with the uploaded parts kept', async () => {
    const id = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    useUploadQueue
      .getState()
      .patch(id, { state: 'UPLOADING', mediaId: 'media-1', parts: { 1: '"a"', 2: '"b"' } });

    // What a restart does: build the state again from what was written to storage.
    await useUploadQueue.persist.rehydrate();

    expect(useUploadQueue.getState().entries).toHaveLength(1);
    expect(useUploadQueue.getState().entries[0]).toMatchObject({
      localId: id,
      ownerId: 'user-1',
      mediaId: 'media-1',
      parts: { 1: '"a"', 2: '"b"' },
      state: 'FAILED', // it was mid-upload when the app stopped
      error: 'Interrupted before it finished. Retry to continue.',
    });
  });

  it('after a restart nothing is in flight: interrupted uploads are FAILED and can be retried', () => {
    const base = { parts: { 1: '"a"' }, error: null } as unknown as UploadEntry;
    const restored = afterRestart([
      { ...base, localId: 'a', state: 'UPLOADING' },
      { ...base, localId: 'b', state: 'COMPLETING' },
      { ...base, localId: 'c', state: 'PROCESSING' },
      { ...base, localId: 'd', state: 'READY' },
      { ...base, localId: 'e', state: 'QUEUED' },
    ]);

    expect(restored.map((e) => e.state)).toEqual([
      'FAILED',
      'FAILED',
      'PROCESSING',
      'READY',
      'FAILED',
    ]);
    expect(restored[0]).toMatchObject({
      error: 'Interrupted before it finished. Retry to continue.',
      parts: { 1: '"a"' }, // the uploaded parts are kept: retrying sends only what is missing
    });
    expect(restored[2]!.error).toBeNull();
  });
});
