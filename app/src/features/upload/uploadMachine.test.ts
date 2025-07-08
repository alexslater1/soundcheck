import type { Playback, UploadTicket } from '@/api/generated/model';

import type { UploadEntry, UploadTransport } from './types';
import { PARALLEL_PARTS, runUpload, type UploadDeps } from './uploadMachine';

const MIB = 1024 * 1024;

function entry(overrides: Partial<UploadEntry> = {}): UploadEntry {
  return {
    localId: 'local-1',
    idempotencyKey: 'key-1',
    createdAt: 0,
    fileUri: 'file:///clip.mp4',
    fileName: 'clip.mp4',
    contentType: 'video/mp4',
    bytes: 20 * MIB,
    durationMs: 20_000,
    width: 720,
    height: 1280,
    clientMeta: { platform: 'android', originalBytes: 60 * MIB, compressedBytes: 20 * MIB },
    temporary: false,
    prepared: true,
    previewUri: 'file:///clip.mp4',
    state: 'QUEUED',
    progress: 0,
    mediaId: null,
    strategy: null,
    parts: {},
    error: null,
    draft: null,
    postIdempotencyKey: 'post-key-1',
    postId: null,
    postError: null,
    ...overrides,
  };
}

/** A 20 MiB file in parts of 8 MiB: 8 + 8 + 4. */
const multipartTicket: UploadTicket = {
  mediaId: 'media-1',
  strategy: 'MULTIPART',
  partSize: 8 * MIB,
  parts: [1, 2, 3].map((partNumber) => ({ partNumber, url: `https://s3.test/part-${partNumber}` })),
  expiresAt: '2026-10-02T12:00:00Z',
};

const singleTicket: UploadTicket = {
  mediaId: 'media-1',
  strategy: 'SINGLE',
  putUrl: 'https://s3.test/whole',
  headers: { 'Content-Type': 'video/mp4' },
  expiresAt: '2026-10-02T12:00:00Z',
};

/** The machine wired to an in-memory entry, with every collaborator a jest mock. */
function harness(options: {
  start?: UploadEntry;
  ticket: UploadTicket;
  statuses?: Playback['status'][];
  putPart?: UploadTransport['putPart'];
  putWhole?: UploadTransport['putWhole'];
}) {
  let state = options.start ?? entry();
  const history: UploadEntry['state'][] = [];
  const statuses = [...(options.statuses ?? ['READY'])];
  const sleeps: number[] = [];
  const deps = {
    api: {
      createUpload: jest.fn(async () => options.ticket),
      completeUpload: jest.fn(async () => ({ status: 'UPLOADED' })),
      getMedia: jest.fn(async (): Promise<Playback> => ({ status: statuses.shift() ?? 'READY' })),
    },
    transport: {
      putWhole: jest.fn(options.putWhole ?? (async () => undefined)),
      putPart: jest.fn(
        options.putPart ?? (async (url: string) => `"etag-${url.slice(url.lastIndexOf('-') + 1)}"`),
      ),
      discardLeftovers: jest.fn(),
    },
    current: () => state,
    update: (patch: Partial<UploadEntry>) => {
      state = { ...state, ...patch };
      if (patch.state && history.at(-1) !== patch.state) {
        history.push(patch.state);
      }
    },
    sleep: async (ms: number) => {
      sleeps.push(ms);
    },
    random: () => 0,
  } satisfies UploadDeps;
  return { deps, history, sleeps, entry: () => state };
}

describe('runUpload', () => {
  it('uploads a small file with one PUT, completes, and follows the worker to READY', async () => {
    const h = harness({
      start: entry({ bytes: 3 * MIB }),
      ticket: singleTicket,
      statuses: ['UPLOADED', 'PROCESSING', 'READY'],
    });

    await runUpload(h.deps);

    expect(h.deps.api.createUpload).toHaveBeenCalledWith(
      expect.objectContaining({
        kind: 'VIDEO',
        contentType: 'video/mp4',
        bytes: 3 * MIB,
        durationMs: 20_000,
        clientMeta: expect.objectContaining({ platform: 'android' }),
      }),
      'key-1',
    );
    expect(h.deps.transport.putWhole).toHaveBeenCalledWith(
      'https://s3.test/whole',
      { 'Content-Type': 'video/mp4' },
      { uri: 'file:///clip.mp4', bytes: 3 * MIB },
    );
    expect(h.deps.transport.putPart).not.toHaveBeenCalled();
    expect(h.deps.api.completeUpload).toHaveBeenCalledWith('media-1', null);
    expect(h.history).toEqual(['REQUESTING', 'UPLOADING', 'COMPLETING', 'PROCESSING', 'READY']);
    expect(h.sleeps).toEqual([1000, 2000]); // polled three times: 1 s, then 2 s apart
    expect(h.entry()).toMatchObject({
      state: 'READY',
      mediaId: 'media-1',
      progress: 1,
      error: null,
    });
  });

  it('uploads a large file in parts with the right byte ranges and completes with every ETag in order', async () => {
    const h = harness({ ticket: multipartTicket });

    await runUpload(h.deps);

    const source = { uri: 'file:///clip.mp4', bytes: 20 * MIB };
    expect(h.deps.transport.putPart.mock.calls).toEqual(
      expect.arrayContaining([
        ['https://s3.test/part-1', source, 0, 8 * MIB],
        ['https://s3.test/part-2', source, 8 * MIB, 8 * MIB],
        ['https://s3.test/part-3', source, 16 * MIB, 4 * MIB], // the last part is what is left
      ]),
    );
    expect(h.deps.transport.putPart).toHaveBeenCalledTimes(3);
    expect(h.deps.api.completeUpload).toHaveBeenCalledWith('media-1', [
      { partNumber: 1, etag: '"etag-1"' },
      { partNumber: 2, etag: '"etag-2"' },
      { partNumber: 3, etag: '"etag-3"' },
    ]);
    expect(h.entry()).toMatchObject({ state: 'READY', strategy: 'MULTIPART', progress: 1 });
  });

  it('retries a failing part with backoff and still completes', async () => {
    let failuresLeft = 2;
    const h = harness({
      ticket: multipartTicket,
      putPart: async (url) => {
        if (url.endsWith('part-2') && failuresLeft > 0) {
          failuresLeft -= 1;
          throw new Error('network down');
        }
        return `"etag-${url.slice(url.lastIndexOf('-') + 1)}"`;
      },
    });

    await runUpload(h.deps);

    expect(h.deps.transport.putPart).toHaveBeenCalledTimes(5); // 3 parts + 2 retries of part 2
    expect(h.sleeps.slice(0, 2)).toEqual([500, 1000]); // the retry backoff (jitter 0 in this test)
    expect(h.deps.api.completeUpload).toHaveBeenCalledTimes(1);
    expect(h.entry().state).toBe('READY');
  });

  it('fails when a part is out of retries, keeps the parts that made it, and does not complete', async () => {
    const h = harness({
      ticket: multipartTicket,
      putPart: async (url) => {
        if (url.endsWith('part-3')) {
          throw new Error('network down');
        }
        return `"etag-${url.slice(url.lastIndexOf('-') + 1)}"`;
      },
    });

    await runUpload(h.deps);

    expect(h.deps.api.completeUpload).not.toHaveBeenCalled();
    expect(h.deps.api.getMedia).not.toHaveBeenCalled();
    expect(h.entry()).toMatchObject({
      state: 'FAILED',
      error: 'network down',
      mediaId: 'media-1',
      parts: { 1: '"etag-1"', 2: '"etag-2"' },
    });
    expect(h.entry().progress).toBeCloseTo(16 / 20);
    // Part 3: the first try and three retries.
    expect(
      h.deps.transport.putPart.mock.calls.filter(([url]) => url.endsWith('part-3')),
    ).toHaveLength(4);
  });

  it('continues a failed upload from the parts already uploaded', async () => {
    const h = harness({
      start: entry({
        state: 'FAILED',
        error: 'network down',
        mediaId: 'media-1',
        strategy: 'MULTIPART',
        parts: { 1: '"etag-1"', 2: '"etag-2"' },
        progress: 0.8,
      }),
      ticket: multipartTicket,
      statuses: ['AWAITING_UPLOAD', 'READY'], // the server is still waiting for the bytes
    });

    await runUpload(h.deps);

    // The same idempotency key returns the same upload (with fresh URLs); only the missing part is sent.
    expect(h.deps.api.createUpload).toHaveBeenCalledWith(expect.anything(), 'key-1');
    expect(h.deps.transport.putPart.mock.calls.map(([url]) => url)).toEqual([
      'https://s3.test/part-3',
    ]);
    expect(h.deps.api.completeUpload).toHaveBeenCalledWith('media-1', [
      { partNumber: 1, etag: '"etag-1"' },
      { partNumber: 2, etag: '"etag-2"' },
      { partNumber: 3, etag: '"etag-3"' },
    ]);
    expect(h.entry()).toMatchObject({ state: 'READY', error: null });
  });

  it('goes straight to polling when the server already accepted the upload (a complete whose answer was lost)', async () => {
    // The earlier run sent every byte and called complete; the server committed, the response never arrived.
    const h = harness({
      start: entry({
        state: 'FAILED',
        error: 'Network request failed',
        mediaId: 'media-1',
        strategy: 'MULTIPART',
        parts: { 1: '"etag-1"', 2: '"etag-2"', 3: '"etag-3"' },
        progress: 1,
      }),
      ticket: multipartTicket,
      statuses: ['PROCESSING', 'READY'],
    });

    await runUpload(h.deps);

    // Asking for the ticket again would be refused (409 UPLOAD_ALREADY_COMPLETED): it is not asked for.
    expect(h.deps.api.createUpload).not.toHaveBeenCalled();
    expect(h.deps.transport.putPart).not.toHaveBeenCalled();
    expect(h.deps.api.completeUpload).not.toHaveBeenCalled();
    expect(h.entry()).toMatchObject({ state: 'READY', error: null });
  });

  it('uploads again when the server still waits for the bytes, or cannot be asked', async () => {
    const waiting = harness({
      start: entry({ state: 'FAILED', mediaId: 'media-1', strategy: 'SINGLE', bytes: 3 * MIB }),
      ticket: singleTicket,
      statuses: ['AWAITING_UPLOAD', 'READY'],
    });
    await runUpload(waiting.deps);
    expect(waiting.deps.transport.putWhole).toHaveBeenCalledTimes(1);
    expect(waiting.entry().state).toBe('READY');

    const offline = harness({
      start: entry({ state: 'FAILED', mediaId: 'media-1', strategy: 'SINGLE', bytes: 3 * MIB }),
      ticket: singleTicket,
    });
    offline.deps.api.getMedia.mockRejectedValueOnce(new Error('offline'));
    await runUpload(offline.deps);
    expect(offline.deps.api.createUpload).toHaveBeenCalledTimes(1);
    expect(offline.entry().state).toBe('READY');
  });

  it('forgets the old parts when the server answers with a different upload', async () => {
    // A retry more than a day later: the idempotency key was purged, so the same request opens a new
    // upload. The stored ETags are for the old one and must not be sent with the new one.
    const h = harness({
      start: entry({
        state: 'FAILED',
        mediaId: 'media-old',
        strategy: 'MULTIPART',
        parts: { 1: '"old-1"', 2: '"old-2"' },
        progress: 0.8,
      }),
      ticket: multipartTicket, // mediaId 'media-1'
      statuses: ['AWAITING_UPLOAD', 'READY'],
    });

    await runUpload(h.deps);

    expect(h.deps.transport.putPart).toHaveBeenCalledTimes(3);
    expect(h.deps.api.completeUpload).toHaveBeenCalledWith('media-1', [
      { partNumber: 1, etag: '"etag-1"' },
      { partNumber: 2, etag: '"etag-2"' },
      { partNumber: 3, etag: '"etag-3"' },
    ]);
    expect(h.entry()).toMatchObject({ state: 'READY', mediaId: 'media-1' });
  });

  it('never has more than two parts in flight', async () => {
    const parts = Array.from({ length: 10 }, (_, i) => ({
      partNumber: i + 1,
      url: `https://s3.test/part-${i + 1}`,
    }));
    let inFlight = 0;
    let peak = 0;
    const h = harness({
      start: entry({ bytes: 80 * MIB }),
      ticket: { ...multipartTicket, parts },
      putPart: async (url) => {
        inFlight += 1;
        peak = Math.max(peak, inFlight);
        await new Promise((resolve) => setTimeout(resolve, 1));
        inFlight -= 1;
        return `"etag-${url.slice(url.lastIndexOf('-') + 1)}"`;
      },
    });

    await runUpload(h.deps);

    expect(PARALLEL_PARTS).toBe(2);
    expect(peak).toBe(PARALLEL_PARTS);
    expect(h.deps.transport.putPart).toHaveBeenCalledTimes(10);
    expect(h.entry().state).toBe('READY');
  });

  it('sends the bytes inside the gate, and waits for the worker outside it', async () => {
    const order: string[] = [];
    const h = harness({ ticket: singleTicket, statuses: ['PROCESSING', 'READY'] });
    const deps: UploadDeps = {
      ...h.deps,
      api: {
        ...h.deps.api,
        getMedia: async () => {
          order.push('poll');
          return h.deps.api.getMedia();
        },
      },
      oneAtATime: async (step) => {
        order.push('gate in');
        const result = await step();
        order.push('gate out');
        return result;
      },
    };

    await runUpload(deps);

    expect(order).toEqual(['gate in', 'gate out', 'poll', 'poll']);
    expect(h.deps.transport.putWhole).toHaveBeenCalledTimes(1);
    expect(h.entry().state).toBe('READY');
  });

  it('ends REJECTED when the worker rejects the file, and FAILED when processing fails', async () => {
    const rejected = harness({ ticket: singleTicket, statuses: ['PROCESSING', 'REJECTED'] });
    await runUpload(rejected.deps);
    expect(rejected.entry()).toMatchObject({ state: 'REJECTED', error: null });

    const failed = harness({ ticket: singleTicket, statuses: ['FAILED'] });
    await runUpload(failed.deps);
    expect(failed.entry()).toMatchObject({
      state: 'FAILED',
      error: 'Processing failed on the server.',
    });
  });

  it('only polls when the entry was already completed, and does nothing when it is settled', async () => {
    const processing = harness({
      start: entry({ state: 'PROCESSING', mediaId: 'media-1', progress: 1 }),
      ticket: singleTicket,
      statuses: ['READY'],
    });
    await runUpload(processing.deps);
    expect(processing.deps.api.createUpload).not.toHaveBeenCalled();
    expect(processing.deps.transport.putWhole).not.toHaveBeenCalled();
    expect(processing.entry().state).toBe('READY');

    const ready = harness({
      start: entry({ state: 'READY', mediaId: 'media-1' }),
      ticket: singleTicket,
    });
    await runUpload(ready.deps);
    expect(ready.deps.api.getMedia).not.toHaveBeenCalled();
  });

  it('keeps polling through a failed status request and stops asking after fifteen minutes', async () => {
    const h = harness({ ticket: singleTicket });
    let calls = 0;
    h.deps.api.getMedia.mockImplementation(async () => {
      calls += 1;
      if (calls === 1) throw new Error('offline');
      return { status: 'PROCESSING' };
    });

    await runUpload(h.deps);

    expect(calls).toBe(94); // the whole schedule: 1, 2, 4, 8 s, then every 10 s
    expect(h.sleeps.reduce((a, b) => a + b, 0)).toBeGreaterThanOrEqual(15 * 60 * 1000);
    expect(h.entry()).toMatchObject({ state: 'PROCESSING', error: null });
  });

  it('fails with the server message when the ticket is refused', async () => {
    const h = harness({ ticket: singleTicket });
    h.deps.api.createUpload.mockRejectedValue(new Error('Upload limit reached, try again later'));

    await runUpload(h.deps);

    expect(h.deps.transport.putWhole).not.toHaveBeenCalled();
    expect(h.entry()).toMatchObject({
      state: 'FAILED',
      error: 'Upload limit reached, try again later',
      mediaId: null,
    });
  });
});
