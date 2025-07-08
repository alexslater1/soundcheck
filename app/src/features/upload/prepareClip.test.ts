import { prepareClip, type PrepareDeps } from './prepareClip';
import type { UploadEntry } from './types';
import { MAX_BYTES } from './validate';

const MB = 1_000_000;

function setup(
  connection: 'wifi' | 'cellular' | 'unknown',
  compressedBytes: number | null,
  bytes = 140 * MB,
) {
  let entry: UploadEntry | null = {
    localId: 'u1',
    idempotencyKey: 'k1',
    createdAt: 0,
    fileUri: 'file:///DCIM/clip.mp4',
    fileName: 'clip.mp4',
    contentType: 'video/mp4',
    bytes, // by default ~56 s of 1080p at 20 Mbit/s, as the Pixel records
    durationMs: 56_000,
    width: 1080,
    height: 1920,
    clientMeta: { platform: 'android' },
    temporary: false,
    prepared: false,
    previewUri: 'file:///DCIM/clip.mp4',
    state: 'PREPARING',
    progress: 0,
    mediaId: null,
    strategy: null,
    parts: {},
    error: null,
    draft: null,
    postIdempotencyKey: 'pk1',
    postId: null,
    postError: null,
  };
  const updates: Partial<UploadEntry>[] = [];
  const deps: PrepareDeps & { compress: jest.Mock; discard: jest.Mock } = {
    current: () => {
      if (!entry) throw new Error('removed');
      return entry;
    },
    update: (patch) => {
      updates.push(patch);
      if (entry) entry = { ...entry, ...patch };
    },
    connection: async () => connection,
    compress: jest.fn(async (_video, _plan, onProgress: (f: number) => void) => {
      onProgress(0.5);
      return compressedBytes === null
        ? null
        : { uri: 'file:///cache/out.mp4', bytes: compressedBytes, elapsedMs: 24_800 };
    }),
    discard: jest.fn(),
    platform: 'android',
  };
  return {
    deps,
    updates,
    entry: () => entry,
    remove: () => {
      entry = null;
    },
  };
}

describe('prepareClip', () => {
  it('on Wi-Fi sends the original at once, without compressing', async () => {
    const t = setup('wifi', 57 * MB);

    expect(await prepareClip(t.deps)).toBe(true);

    expect(t.deps.compress).not.toHaveBeenCalled();
    expect(t.entry()).toMatchObject({
      state: 'QUEUED',
      prepared: true,
      fileUri: 'file:///DCIM/clip.mp4',
      bytes: 140 * MB,
      temporary: false,
      clientMeta: { platform: 'android', connection: 'wifi' },
      previewUri: 'file:///DCIM/clip.mp4',
    });
  });

  it('on mobile data compresses in the background, reporting progress, and sends the smaller file', async () => {
    const t = setup('cellular', 57 * MB);

    expect(await prepareClip(t.deps)).toBe(true);

    expect(t.updates).toContainEqual({ progress: 0.5 });
    expect(t.entry()).toMatchObject({
      state: 'QUEUED',
      prepared: true,
      fileUri: 'file:///cache/out.mp4',
      bytes: 57 * MB,
      temporary: true,
      clientMeta: {
        connection: 'cellular',
        originalBytes: 140 * MB,
        compressedBytes: 57 * MB,
        compressMs: 24_800,
      },
      // The form keeps showing the original.
      previewUri: 'file:///DCIM/clip.mp4',
    });
  });

  it('keeps the original when compression did not make it smaller', async () => {
    const t = setup('unknown', 150 * MB);

    await prepareClip(t.deps);

    expect(t.deps.discard).toHaveBeenCalledWith('file:///cache/out.mp4');
    expect(t.entry()).toMatchObject({ fileUri: 'file:///DCIM/clip.mp4', temporary: false });
  });

  it('compresses a file over 1 GB on Wi-Fi too, and sends the smaller file', async () => {
    const t = setup('wifi', 180 * MB, MAX_BYTES + 1);

    expect(await prepareClip(t.deps)).toBe(true);

    expect(t.deps.compress).toHaveBeenCalledTimes(1);
    expect(t.entry()).toMatchObject({
      state: 'QUEUED',
      fileUri: 'file:///cache/out.mp4',
      bytes: 180 * MB,
      temporary: true,
    });
  });

  it('fails, ready to try again, when a file over 1 GB could not be compressed', async () => {
    const t = setup('wifi', null, MAX_BYTES + 1);

    expect(await prepareClip(t.deps)).toBe(false);

    expect(t.entry()).toMatchObject({
      state: 'FAILED',
      prepared: false,
      fileUri: 'file:///DCIM/clip.mp4',
      error: 'This video is larger than 1 GB and could not be compressed.',
    });
    expect(t.deps.discard).not.toHaveBeenCalled(); // the user's own file stays
  });

  it('throws away its work when the clip was removed meanwhile', async () => {
    const t = setup('cellular', 57 * MB);
    t.deps.compress.mockImplementationOnce(async () => {
      t.remove();
      return { uri: 'file:///cache/out.mp4', bytes: 57 * MB, elapsedMs: 1 };
    });

    expect(await prepareClip(t.deps)).toBe(false);

    expect(t.deps.discard).toHaveBeenCalledWith('file:///cache/out.mp4');
    expect(t.updates.some((u) => u.state === 'QUEUED')).toBe(false);
  });
});
