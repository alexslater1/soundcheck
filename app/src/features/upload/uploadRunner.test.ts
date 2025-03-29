import type { PickedVideo } from './types';
import { useUploadQueue } from './uploadQueue';
import { removeUpload, resumeProcessing, startUpload, submitPost } from './uploadRunner';

const mockCreateUpload = jest.fn();
const mockCompleteUpload = jest.fn();
const mockGetMedia = jest.fn();
const mockPutWhole = jest.fn();
const mockDiscard = jest.fn();
const mockCreatePost = jest.fn();

jest.mock('@/api/generated/media/media', () => ({
  createUpload: (...args: unknown[]) => mockCreateUpload(...args),
  completeUpload: (...args: unknown[]) => mockCompleteUpload(...args),
  getMedia: (...args: unknown[]) => mockGetMedia(...args),
}));
jest.mock('./transport', () => ({
  transport: { putWhole: (...args: unknown[]) => mockPutWhole(...args), putPart: jest.fn() },
}));
jest.mock('./compress', () => ({ discardFile: (uri: string) => mockDiscard(uri) }));
jest.mock('@/api/generated/posts/posts', () => ({
  createPost: (...args: unknown[]) => mockCreatePost(...args),
}));

const video: PickedVideo = {
  uri: 'file:///cache/compressed.mp4',
  fileName: 'clip.mp4',
  contentType: 'video/mp4',
  bytes: 3_000_000,
  durationMs: 12_000,
  width: null,
  height: null,
};

/** Lets the runner's un-awaited post request finish. */
async function waitForPost(): Promise<void> {
  for (let i = 0; i < 20 && !useUploadQueue.getState().entries[0]?.postId; i++) {
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
    mockCreatePost.mockResolvedValue({ id: 'post-1' });
  });

  it('posts as soon as the upload completes when Post was pressed first, without waiting for READY', async () => {
    const id = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    await submitPost(id, { caption: 'Encore', gigId: 'gig-1', artistIds: [] });
    expect(mockCreatePost).not.toHaveBeenCalled(); // nothing uploaded yet
    // The worker is still busy when the post is created.
    mockGetMedia.mockImplementation(async () => {
      expect(mockCreatePost).toHaveBeenCalledTimes(1);
      return { status: 'READY' };
    });

    await startUpload(id);

    const key = useUploadQueue.getState().entries[0]!.postIdempotencyKey;
    expect(mockCreatePost).toHaveBeenCalledWith(
      { mediaId: 'media-1', caption: 'Encore', gigId: 'gig-1', artistIds: [] },
      { headers: { 'Idempotency-Key': key } },
    );
    expect(useUploadQueue.getState().entries[0]).toMatchObject({
      postId: 'post-1',
      state: 'READY',
    });
  });

  it('posts at once when Post is pressed after the upload completed, and only once', async () => {
    const id = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    await startUpload(id);
    expect(mockCreatePost).not.toHaveBeenCalled(); // no draft yet

    await submitPost(id, { caption: '', gigId: null, artistIds: [] });
    await submitPost(id, { caption: '', gigId: null, artistIds: [] });

    expect(mockCreatePost).toHaveBeenCalledTimes(1);
    expect(useUploadQueue.getState().entries[0]!.postId).toBe('post-1');
  });

  it('a post whose request failed is sent again with the same key, by Post again and when the queue resumes', async () => {
    const id = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });
    await startUpload(id);
    const draft = { caption: 'Encore', gigId: null, artistIds: [] };
    mockCreatePost.mockRejectedValueOnce(new Error('Network request failed'));

    await submitPost(id, draft);
    expect(useUploadQueue.getState().entries[0]).toMatchObject({
      postId: null,
      postError: 'Network request failed',
    });

    // The app is opened again later: the queue is resumed and the post goes through.
    resumeProcessing('user-1');
    await waitForPost();

    expect(mockCreatePost).toHaveBeenCalledTimes(2);
    const keys = mockCreatePost.mock.calls.map(
      (call) => (call[1] as { headers: Record<string, string> }).headers['Idempotency-Key'],
    );
    expect(keys[0]).toBe(keys[1]);
    expect(useUploadQueue.getState().entries[0]).toMatchObject({
      postId: 'post-1',
      postError: null,
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
    removeUpload(id);

    expect(mockDiscard).not.toHaveBeenCalled();
    expect(useUploadQueue.getState().entries).toHaveLength(0);
  });

  it('removing an unfinished upload deletes its temporary file too', () => {
    const id = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' }, true);

    removeUpload(id);

    expect(mockDiscard).toHaveBeenCalledWith('file:///cache/compressed.mp4');
    expect(useUploadQueue.getState().entries).toHaveLength(0);
  });

  it('does not run the same upload twice at once', async () => {
    const id = useUploadQueue.getState().enqueue('user-1', video, { platform: 'android' });

    await Promise.all([startUpload(id), startUpload(id)]);

    expect(mockCreateUpload).toHaveBeenCalledTimes(1);
  });
});
