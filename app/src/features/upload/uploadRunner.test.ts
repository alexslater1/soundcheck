import type { PickedVideo } from './types';
import { useUploadQueue } from './uploadQueue';
import {
  forgetPost,
  makePreview,
  pruneFinished,
  removeUpload,
  resumeProcessing,
  retryPost,
  startUpload,
  submitPost,
} from './uploadRunner';

const mockCreateUpload = jest.fn();
const mockCompleteUpload = jest.fn();
const mockGetMedia = jest.fn();
const mockPutWhole = jest.fn();
const mockDiscardLeftovers = jest.fn();
const mockDiscard = jest.fn();
const mockCreatePost = jest.fn();
const mockDeletePost = jest.fn();
const mockAddPostClips = jest.fn();
const mockFillPostClip = jest.fn();
const mockRemovePostClip = jest.fn();
const mockPreviewFrame = jest.fn();
const mockUploadImage = jest.fn();

jest.mock('@/api/generated/media/media', () => ({
  createUpload: (...args: unknown[]) => mockCreateUpload(...args),
  completeUpload: (...args: unknown[]) => mockCompleteUpload(...args),
  getMedia: (...args: unknown[]) => mockGetMedia(...args),
}));
jest.mock('./transport', () => ({
  transport: {
    putWhole: (...args: unknown[]) => mockPutWhole(...args),
    putPart: jest.fn(),
    discardLeftovers: () => mockDiscardLeftovers(),
  },
}));
jest.mock('./compress', () => ({ discardFile: (uri: string) => mockDiscard(uri) }));
jest.mock('@/api/generated/posts/posts', () => ({
  createPost: (...args: unknown[]) => mockCreatePost(...args),
  deletePost: (...args: unknown[]) => mockDeletePost(...args),
  addPostClips: (...args: unknown[]) => mockAddPostClips(...args),
  fillPostClip: (...args: unknown[]) => mockFillPostClip(...args),
  removePostClip: (...args: unknown[]) => mockRemovePostClip(...args),
}));
jest.mock('./previewFrame', () => ({
  previewFrame: (...args: unknown[]) => mockPreviewFrame(...args),
}));
jest.mock('./uploadImage', () => ({
  uploadImage: (...args: unknown[]) => mockUploadImage(...args),
}));

/** The post the server answers with: one slot per clip of `user-1`. */
const postWith = (...slots: string[]) => ({
  id: 'post-1',
  clips: slots.map((id, position) => ({ id, position, owner: { id: 'user-1' } })),
});

/** What the user chose for a post of these uploads. */
const input = (localIds: string[], over: Partial<Parameters<typeof submitPost>[0]> = {}) => ({
  caption: 'Encore',
  gigId: 'gig-1',
  day: null,
  taggedUserIds: [],
  joinPostId: null,
  clips: localIds.map((localId) => ({ localId, artistId: 'artist-1' })),
  ...over,
});

const video: PickedVideo = {
  uri: 'file:///cache/compressed.mp4',
  fileName: 'clip.mp4',
  contentType: 'video/mp4',
  bytes: 3_000_000,
  durationMs: 12_000,
  width: null,
  height: null,
};

/** Lets the runner's un-awaited post requests finish. */
async function waitFor(done: () => boolean): Promise<void> {
  for (let i = 0; i < 40 && !done(); i++) {
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

describe('upload runner', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useUploadQueue.setState({ entries: [] });
    mockCreateUpload.mockResolvedValue({
      mediaId: 'media-1',
      strategy: 'SINGLE',
      putUrl: 'https://s3.test/whole',
      headers: { 'Content-Type': 'video/mp4' },
      expiresAt: '2026-10-02T12:00:00Z',
    });
    mockCompleteUpload.mockResolvedValue({ status: 'UPLOADED' });
    mockGetMedia.mockResolvedValue({ status: 'READY' });
    mockPutWhole.mockResolvedValue(undefined);
    mockCreatePost.mockResolvedValue(postWith('slot-1'));
    mockFillPostClip.mockResolvedValue(postWith('slot-1'));
  });

  it('makes the post when Post is pressed, and puts the clip in its slot once the upload completes', async () => {
    const id = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    await submitPost(input([id]));

    const key = useUploadQueue.getState().entries[0]!.draft!.groupKey;
    expect(mockCreatePost).toHaveBeenCalledWith(
      {
        clips: [{ mediaId: null, artistId: 'artist-1', previewMediaId: null }],
        caption: 'Encore',
        gigId: 'gig-1',
        day: null,
        artistIds: ['artist-1'],
        taggedUserIds: [],
      },
      { headers: { 'Idempotency-Key': key } },
    );
    expect(useUploadQueue.getState().entries[0]).toMatchObject({
      postId: 'post-1',
      clipId: 'slot-1',
      attached: false,
    });
    // The worker is still busy when the clip goes into its slot.
    mockGetMedia.mockImplementation(async () => {
      expect(mockFillPostClip).toHaveBeenCalledWith('post-1', 'slot-1', { mediaId: 'media-1' });
      return { status: 'READY' };
    });

    await startUpload(id);

    expect(mockCreatePost).toHaveBeenCalledTimes(1);
    expect(useUploadQueue.getState().entries[0]).toMatchObject({ attached: true, state: 'READY' });
  });

  it('several clips make one post, each in its own slot, in the order announced', async () => {
    const a = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    const b = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    mockCreatePost.mockResolvedValue(postWith('slot-b', 'slot-a'));

    await submitPost(input([b, a]));

    expect(mockCreatePost).toHaveBeenCalledTimes(1);
    expect(mockCreatePost.mock.calls[0]![0].clips).toHaveLength(2);
    const byId = (localId: string) =>
      useUploadQueue.getState().entries.find((e) => e.localId === localId)!;
    expect(byId(b).clipId).toBe('slot-b');
    expect(byId(a).clipId).toBe('slot-a');
    expect(byId(a).draft!.groupKey).toBe(byId(b).draft!.groupKey);
  });

  it('a post whose request failed is sent again with the same key, by Post again and when the queue resumes', async () => {
    const id = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    await startUpload(id);
    mockCreatePost.mockRejectedValueOnce(new Error('Network request failed'));

    await submitPost(input([id]));
    expect(useUploadQueue.getState().entries[0]).toMatchObject({
      postId: null,
      postError: 'Network request failed',
    });
    mockCreatePost.mockRejectedValueOnce(new Error('Network request failed'));
    await retryPost(id);

    // The app is opened again later: the queue is resumed and the post goes through.
    resumeProcessing('user-1');
    await waitFor(() => useUploadQueue.getState().entries[0]?.postId != null);

    expect(mockCreatePost).toHaveBeenCalledTimes(3);
    const keys = mockCreatePost.mock.calls.map(
      (call) => (call[1] as { headers: Record<string, string> }).headers['Idempotency-Key'],
    );
    expect(new Set(keys).size).toBe(1);
    expect(useUploadQueue.getState().entries[0]).toMatchObject({
      postId: 'post-1',
      attached: true,
      postError: null,
    });
  });

  it('adds the clips to an existing post when asked to', async () => {
    const id = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    mockAddPostClips.mockResolvedValue({
      id: 'their-post',
      clips: [
        { id: 'theirs', position: 0, owner: { id: 'author' } },
        { id: 'mine', position: 1, owner: { id: 'user-1' } },
      ],
    });

    await submitPost(input([id], { joinPostId: 'their-post' }));

    expect(mockCreatePost).not.toHaveBeenCalled();
    expect(mockAddPostClips).toHaveBeenCalledWith('their-post', {
      clips: [{ mediaId: null, artistId: 'artist-1', previewMediaId: null }],
    });
    expect(useUploadQueue.getState().entries[0]).toMatchObject({
      postId: 'their-post',
      clipId: 'mine',
    });
  });

  it('uploads a preview frame and gives it to the clip’s slot; a frame that cannot be made is left out', async () => {
    const id = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    await submitPost(input([id]));
    mockPreviewFrame.mockResolvedValue({
      uri: 'file:///thumb.jpg',
      contentType: 'image/jpeg',
      bytes: 9,
    });
    mockUploadImage.mockResolvedValue('img-1');

    await makePreview(id);

    expect(mockUploadImage).toHaveBeenCalled();
    expect(mockFillPostClip).toHaveBeenCalledWith('post-1', 'slot-1', { previewMediaId: 'img-1' });
    expect(useUploadQueue.getState().entries[0]).toMatchObject({
      previewState: 'READY',
      previewSent: true,
    });

    const other = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    mockPreviewFrame.mockResolvedValue(null);
    await makePreview(other);
    expect(useUploadQueue.getState().entries.find((e) => e.localId === other)).toMatchObject({
      previewState: 'FAILED',
    });
  });

  it('sends the idempotency key as a header and writes every change to the queue', async () => {
    const id = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    const key = useUploadQueue.getState().entries[0]!.idempotencyKey;

    await startUpload(id);

    expect(mockCreateUpload).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'VIDEO', bytes: 3_000_000 }),
      { headers: { 'Idempotency-Key': key } },
    );
    expect(mockCompleteUpload).toHaveBeenCalledWith('media-1', undefined);
    expect(useUploadQueue.getState().entries[0]).toMatchObject({
      state: 'READY',
      mediaId: 'media-1',
      progress: 1,
    });
  });

  it('deletes the compressed temporary file once the server has the bytes, and only then', async () => {
    const id = useUploadQueue
      .getState()
      .enqueue('user-1', video, { platform: 'android' }, /* temporary */ true);
    mockPutWhole.mockRejectedValue(new Error('network down'));
    jest.useFakeTimers();
    const failing = startUpload(id);
    await jest.runAllTimersAsync(); // the retry backoff
    await failing;
    jest.useRealTimers();
    // The upload failed: the file is still needed for the retry.
    expect(useUploadQueue.getState().entries[0]!.state).toBe('FAILED');
    expect(mockDiscard).not.toHaveBeenCalled();

    mockPutWhole.mockResolvedValue(undefined);
    mockGetMedia
      .mockResolvedValueOnce({ status: 'AWAITING_UPLOAD' })
      .mockResolvedValue({ status: 'READY' });
    await startUpload(id);

    expect(mockDiscard).toHaveBeenCalledWith('file:///cache/compressed.mp4');
    expect(useUploadQueue.getState().entries[0]).toMatchObject({
      state: 'READY',
      temporary: false,
    });
  });

  it('never deletes a file the user picked', async () => {
    const id = useUploadQueue
      .getState()
      .enqueue('user-1', { ...video, uri: 'file:///DCIM/original.mp4' }, { platform: 'android' });

    await startUpload(id);
    await removeUpload(id);

    expect(mockDiscard).not.toHaveBeenCalled();
    expect(useUploadQueue.getState().entries).toHaveLength(0);
  });

  it('removing an unfinished upload deletes its temporary file too', async () => {
    const id = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' }, true);

    await removeUpload(id);

    expect(mockDiscard).toHaveBeenCalledWith('file:///cache/compressed.mp4');
    expect(useUploadQueue.getState().entries).toHaveLength(0);
  });

  it('does not run the same upload twice at once', async () => {
    const id = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });

    await Promise.all([startUpload(id), startUpload(id)]);

    expect(mockCreateUpload).toHaveBeenCalledTimes(1);
  });

  it('sends one clip’s bytes at a time: the second waits until the first has completed', async () => {
    const order: string[] = [];
    let finishFirst: () => void = () => undefined;
    mockCreateUpload
      .mockResolvedValueOnce({
        mediaId: 'media-1',
        strategy: 'SINGLE',
        putUrl: 'https://s3.test/one',
        expiresAt: '2026-10-02T12:00:00Z',
      })
      .mockResolvedValueOnce({
        mediaId: 'media-2',
        strategy: 'SINGLE',
        putUrl: 'https://s3.test/two',
        expiresAt: '2026-10-02T12:00:00Z',
      });
    mockPutWhole.mockImplementation(async (url: string) => {
      order.push(`put ${url}`);
      if (url.endsWith('one')) {
        await new Promise<void>((resolve) => (finishFirst = resolve));
      }
    });
    mockCompleteUpload.mockImplementation(async (mediaId: string) => {
      order.push(`complete ${mediaId}`);
      return { status: 'UPLOADED' };
    });
    const one = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    const two = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });

    const both = Promise.all([startUpload(one), startUpload(two)]);
    for (let i = 0; i < 20 && order.length === 0; i++) {
      await new Promise((resolve) => setTimeout(resolve, 1));
    }
    await new Promise((resolve) => setTimeout(resolve, 5));
    expect(order).toEqual(['put https://s3.test/one']);
    expect(mockCreateUpload).toHaveBeenCalledTimes(1);

    finishFirst();
    await both;
    expect(order).toEqual([
      'put https://s3.test/one',
      'complete media-1',
      'put https://s3.test/two',
      'complete media-2',
    ]);
    // Leftover part files are cleared only when the first upload starts, never under a running one.
    expect(mockDiscardLeftovers).toHaveBeenCalledTimes(1);
  });

  it('clears leftover part files before an upload starts while nothing else runs', async () => {
    const id = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });

    await startUpload(id);
    await startUpload(id); // settled: runs again, still alone

    expect(mockDiscardLeftovers).toHaveBeenCalledTimes(2);
  });

  describe('keeping the Create list and the profile in step', () => {
    const posted = (
      postId: string,
      state: 'PROCESSING' | 'READY',
      clipId: string | null = null,
    ) => {
      const id = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
      useUploadQueue
        .getState()
        .patch(id, { postId, clipId, attached: true, state, mediaId: `m-${postId}` });
      return id;
    };

    it('removing an upload removes its clip from the post, or the post with its last clip', async () => {
      const { ApiError } = jest.requireActual<typeof import('@/api/http')>('@/api/http');
      mockRemovePostClip.mockResolvedValueOnce(undefined);
      const one = posted('p1', 'READY', 'c1');
      await removeUpload(one);
      expect(mockRemovePostClip).toHaveBeenCalledWith('p1', 'c1');
      expect(mockDeletePost).not.toHaveBeenCalled();

      mockRemovePostClip.mockRejectedValueOnce(new ApiError(409, 'LAST_CLIP', 'last'));
      mockDeletePost.mockResolvedValue(undefined);
      const last = posted('p1', 'READY', 'c2');
      await removeUpload(last);
      expect(mockDeletePost).toHaveBeenCalledWith('p1');
      expect(useUploadQueue.getState().entries).toHaveLength(0);
    });

    it('removing an upload posted as a post of its own deletes its post', async () => {
      mockDeletePost.mockResolvedValue(undefined);
      const id = posted('p1', 'PROCESSING');

      await removeUpload(id);

      expect(mockDeletePost).toHaveBeenCalledWith('p1');
      expect(useUploadQueue.getState().entries).toHaveLength(0);
    });

    it('a post already gone is fine; an unreachable server keeps the entry', async () => {
      const { ApiError } = jest.requireActual<typeof import('@/api/http')>('@/api/http');
      const gone = posted('p2', 'READY');
      mockDeletePost.mockRejectedValueOnce(new ApiError(404, 'POST_NOT_FOUND', 'gone'));
      await removeUpload(gone);
      expect(useUploadQueue.getState().entries).toHaveLength(0);

      const kept = posted('p3', 'READY');
      mockDeletePost.mockRejectedValueOnce(new TypeError('Network request failed'));
      await expect(removeUpload(kept)).rejects.toThrow('Network request failed');
      expect(useUploadQueue.getState().entries).toHaveLength(1);
    });

    it('deleting a post elsewhere removes its upload', () => {
      posted('p4', 'READY');
      const other = posted('p5', 'READY');

      forgetPost('p4');

      expect(useUploadQueue.getState().entries.map((e) => e.localId)).toEqual([other]);
      expect(mockDeletePost).not.toHaveBeenCalled();
    });

    it('drops uploads whose post is up and playable, and keeps the rest', () => {
      posted('p6', 'READY');
      const processing = posted('p7', 'PROCESSING');
      const unposted = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
      useUploadQueue.getState().patch(unposted, { state: 'READY' });

      expect(pruneFinished('user-1')).toBe(1);

      expect(
        useUploadQueue
          .getState()
          .entries.map((e) => e.localId)
          .sort(),
      ).toEqual([processing, unposted].sort());
    });
  });
});
