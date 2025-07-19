import { testDraft } from './testDrafts';
import { bannerText, plainReason, problemOf, problemsOf, type UploadProblem } from './problems';
import type { UploadEntry } from './types';

const entry = (patch: Partial<UploadEntry> = {}): UploadEntry & { ownerId: string } => ({
  localId: 'u1',
  ownerId: 'me',
  idempotencyKey: 'k1',
  createdAt: 0,
  fileUri: 'file:///clip.mp4',
  fileName: 'clip.mp4',
  contentType: 'video/mp4',
  bytes: 1_000_000,
  durationMs: 10_000,
  width: null,
  height: null,
  clientMeta: { platform: 'android' },
  temporary: false,
  prepared: true,
  previewUri: 'file:///clip.mp4',
  state: 'UPLOADING',
  progress: 0.5,
  mediaId: 'm1',
  strategy: 'MULTIPART',
  parts: {},
  error: null,
  draft: null,
  postIdempotencyKey: 'pk1',
  postId: null,
  clipId: null,
  attached: false,
  artistId: null,
  previewMediaId: null,
  previewState: 'NONE',
  previewSent: false,
  postError: null,
  ...patch,
});

describe('plainReason', () => {
  it.each([
    [
      'java.lang.OutOfMemoryError: Failed to allocate a 8388624 byte allocation with 6291456 free bytes',
      'the phone ran out of memory',
    ],
    ['Unable to upload a file: timeout', 'the connection dropped'],
    ['Unable to upload a file: Unable to resolve host "s3.test"', 'the connection dropped'],
    ['Could not reach the server', 'the connection dropped'],
    ['Storage answered 403', 'the upload link expired'],
    ['Storage answered 503', 'storage was not available'],
    ['Interrupted before it finished. Retry to continue.', 'the app was closed before it finished'],
    ['The video file is shorter than expected', 'the video is no longer on the phone'],
    ['Unable to upload a file: File does not exist', 'the video is no longer on the phone'],
    ['Processing failed on the server.', 'the server could not process the clip'],
    ['Too many requests; try again later', 'too many uploads in the last hour; wait a little'],
    [
      'This video is larger than 1 GB and could not be compressed.',
      'the video is over 1 GB and the phone could not compress it',
    ],
    ['Clips can be at most 3 minutes', 'clips can be at most 3 minutes'],
    ['Videos can be at most 1 GB.', 'videos can be at most 1 GB'],
    ['Something nobody expected', 'something went wrong'],
    [null, 'something went wrong'],
  ])('%s → %s', (message, reason) => {
    expect(plainReason(message)).toBe(reason);
  });
});

describe('problemOf', () => {
  it('names a failed upload in plain words and keeps the technical message for Details', () => {
    expect(problemOf(entry({ state: 'FAILED', error: 'java.lang.OutOfMemoryError: x' }))).toEqual({
      localId: 'u1',
      kind: 'upload',
      name: 'clip.mp4',
      reason: 'the phone ran out of memory',
      details: 'java.lang.OutOfMemoryError: x',
    });
  });

  it('counts a rejected clip and a failed post, and nothing that is still going or done', () => {
    expect(problemOf(entry({ state: 'REJECTED' }))?.kind).toBe('rejected');
    const draft = testDraft();
    expect(
      problemOf(entry({ state: 'PROCESSING', draft, postError: 'Could not reach the server' })),
    ).toMatchObject({ kind: 'post', reason: 'the connection dropped' });
    expect(problemOf(entry())).toBeNull();
    expect(problemOf(entry({ state: 'READY', postId: 'p1' }))).toBeNull();
    expect(problemOf(entry({ state: 'PROCESSING', draft }))).toBeNull();
  });

  it('only looks at the signed-in account’s uploads', () => {
    const entries = [
      entry({ localId: 'a', state: 'FAILED', error: 'timeout' }),
      { ...entry({ localId: 'b', state: 'FAILED', error: 'timeout' }), ownerId: 'someone-else' },
    ];
    expect(problemsOf(entries, 'me').map((problem) => problem.localId)).toEqual(['a']);
  });
});

describe('bannerText', () => {
  const problem = (patch: Partial<UploadProblem>): UploadProblem => ({
    localId: 'u1',
    kind: 'upload',
    name: 'clip.mp4',
    reason: 'the phone ran out of memory',
    details: 'x',
    ...patch,
  });

  it('says what happened and what to do', () => {
    expect(bannerText([problem({})])).toBe(
      'Upload failed — the phone ran out of memory. Try again',
    );
    expect(bannerText([problem({ kind: 'post', reason: 'the connection dropped' })])).toBe(
      'Posting failed — the connection dropped. Try again',
    );
    expect(
      bannerText([problem({ kind: 'rejected', reason: 'the server could not use this file' })]),
    ).toBe('This clip could not be used — the server could not use this file. Remove it below.');
  });

  it('groups several uploads by their cause', () => {
    expect(bannerText([problem({ localId: 'a' }), problem({ localId: 'b' })])).toBe(
      '2 uploads failed — the phone ran out of memory. Try again',
    );
    expect(
      bannerText([
        problem({ localId: 'a' }),
        problem({ localId: 'b', reason: 'the link expired' }),
      ]),
    ).toBe('2 uploads need attention.');
  });
});
