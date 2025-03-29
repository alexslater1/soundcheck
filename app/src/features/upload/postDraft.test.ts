import { ensurePost, type PostDeps } from './postDraft';
import type { UploadEntry } from './types';

function harness(overrides: Partial<UploadEntry>) {
  let state = {
    localId: 'local-1',
    mediaId: 'media-1',
    state: 'PROCESSING',
    draft: { caption: '  What a night  ', gigId: 'gig-1', artistIds: ['artist-1'] },
    postIdempotencyKey: 'post-key-1',
    postId: null,
    postError: null,
    ...overrides,
  } as UploadEntry;
  const deps = {
    current: () => state,
    update: (patch: Partial<UploadEntry>) => {
      state = { ...state, ...patch };
    },
    createPost: jest.fn(async () => ({ id: 'post-1' })),
  } satisfies PostDeps;
  return { deps, entry: () => state };
}

describe('ensurePost', () => {
  it('creates the post once the clip is with the server and the user has pressed Post', async () => {
    const h = harness({});

    await ensurePost(h.deps);

    expect(h.deps.createPost).toHaveBeenCalledWith(
      { mediaId: 'media-1', caption: 'What a night', gigId: 'gig-1', artistIds: ['artist-1'] },
      'post-key-1',
    );
    expect(h.entry()).toMatchObject({ postId: 'post-1', postError: null });
  });

  it('waits when either half is missing: no draft yet, or the upload not completed yet', async () => {
    const noDraft = harness({ draft: null });
    await ensurePost(noDraft.deps);
    expect(noDraft.deps.createPost).not.toHaveBeenCalled();

    const stillUploading = harness({ state: 'UPLOADING' });
    await ensurePost(stillUploading.deps);
    expect(stillUploading.deps.createPost).not.toHaveBeenCalled();
  });

  it('does not post twice', async () => {
    const h = harness({ postId: 'post-1' });
    await ensurePost(h.deps);
    expect(h.deps.createPost).not.toHaveBeenCalled();
  });

  it('sends an empty caption as none', async () => {
    const h = harness({ draft: { caption: '   ', gigId: null, artistIds: [] } });
    await ensurePost(h.deps);
    expect(h.deps.createPost).toHaveBeenCalledWith(
      { mediaId: 'media-1', caption: null, gigId: null, artistIds: [] },
      'post-key-1',
    );
  });

  it('keeps the draft and records the error when the server refuses, so Post can be pressed again', async () => {
    const h = harness({});
    h.deps.createPost.mockRejectedValue(new Error('This clip could not be processed'));

    await ensurePost(h.deps);

    expect(h.entry()).toMatchObject({
      postId: null,
      postError: 'This clip could not be processed',
    });
    expect(h.entry().draft).not.toBeNull();
  });
});
