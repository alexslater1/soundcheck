import type { Post, PostClip } from '@/api/generated/model';
import { ApiError } from '@/api/http';

import { newSlots, serialiser, syncGroup, type GroupDeps } from './postDraft';
import { testDraft } from './testDrafts';
import type { UploadEntry } from './types';

const me = 'me';

function entry(localId: string, order: number, over: Partial<UploadEntry> = {}): UploadEntry {
  return {
    localId,
    state: 'UPLOADING',
    mediaId: null,
    draft: testDraft({ groupKey: 'g', order, caption: '  What a night  ', gigId: 'gig-1' }),
    postId: null,
    clipId: null,
    attached: false,
    artistId: null,
    previewMediaId: null,
    previewState: 'NONE',
    previewSent: false,
    postError: null,
    ...over,
  } as UploadEntry;
}

function clip(id: string, position: number, owner = me): PostClip {
  return {
    id,
    position,
    owner: { id: owner, handle: owner, displayName: owner },
    status: 'WAITING',
    playback: { status: 'AWAITING_UPLOAD' },
  } as PostClip;
}

function harness(entries: UploadEntry[], slots: PostClip[]) {
  let state = entries;
  const post = { id: 'post-1', clips: slots } as Post;
  const deps = {
    ownerId: me,
    entries: () => state,
    update: (localId: string, patch: Partial<UploadEntry>) => {
      state = state.map((e) => (e.localId === localId ? { ...e, ...patch } : e));
    },
    createPost: jest.fn(async () => post),
    addPostClips: jest.fn(async () => post),
    fillPostClip: jest.fn(async () => post),
    removePostClip: jest.fn(async () => undefined),
  } satisfies GroupDeps;
  return { deps, entry: (id: string) => state.find((e) => e.localId === id)! };
}

describe('syncGroup', () => {
  it('creates one post for all the clips, with what is there already, and maps each slot back', async () => {
    const h = harness(
      [
        // Stored newest first; announced in `order`.
        entry('b', 1, { artistId: 'headliner' }),
        entry('a', 0, {
          state: 'READY',
          mediaId: 'm-a',
          artistId: 'support',
          previewState: 'READY',
          previewMediaId: 'img-a',
        }),
      ],
      [clip('slot-a', 0), clip('slot-b', 1)],
    );

    await syncGroup(h.deps);

    expect(h.deps.createPost).toHaveBeenCalledTimes(1);
    expect(h.deps.createPost).toHaveBeenCalledWith(
      {
        clips: [
          { mediaId: 'm-a', artistId: 'support', previewMediaId: 'img-a' },
          { mediaId: null, artistId: 'headliner', previewMediaId: null },
        ],
        caption: 'What a night',
        gigId: 'gig-1',
        day: null,
        artistIds: [],
        taggedUserIds: [],
      },
      'g',
    );
    expect(h.entry('a')).toMatchObject({
      postId: 'post-1',
      clipId: 'slot-a',
      attached: true,
      previewSent: true,
    });
    expect(h.entry('b')).toMatchObject({ postId: 'post-1', clipId: 'slot-b', attached: false });
    expect(h.deps.fillPostClip).not.toHaveBeenCalled();
  });

  it('takes the newest slots of mine when the clips joined a post that already had some', () => {
    const post = {
      clips: [clip('old', 0), clip('friend', 1, 'friend'), clip('new-1', 2), clip('new-2', 3)],
    } as Post;
    expect(newSlots(post, me, 2)).toEqual(['new-1', 'new-2']);
  });

  it('fills a slot once its upload completes, and its preview while it is still on its way', async () => {
    const h = harness(
      [entry('a', 0, { postId: 'post-1', clipId: 'slot-a' })],
      [clip('slot-a', 0)],
    );
    await syncGroup(h.deps);
    expect(h.deps.fillPostClip).not.toHaveBeenCalled(); // nothing to send yet

    h.deps.update('a', { previewState: 'READY', previewMediaId: 'img-a' });
    await syncGroup(h.deps);
    expect(h.deps.fillPostClip).toHaveBeenLastCalledWith('post-1', 'slot-a', {
      previewMediaId: 'img-a',
    });

    h.deps.update('a', { state: 'PROCESSING', mediaId: 'm-a' });
    await syncGroup(h.deps);
    await syncGroup(h.deps);
    expect(h.deps.fillPostClip).toHaveBeenCalledTimes(2);
    expect(h.deps.fillPostClip).toHaveBeenLastCalledWith('post-1', 'slot-a', { mediaId: 'm-a' });
    expect(h.entry('a')).toMatchObject({ attached: true, previewSent: true });
    expect(h.deps.createPost).not.toHaveBeenCalled();
  });

  it('a failed create is sent again with the same key and the very same body', async () => {
    const h = harness([entry('a', 0)], [clip('slot-a', 0)]);
    h.deps.createPost.mockRejectedValueOnce(new Error('Network request failed'));

    await syncGroup(h.deps);
    expect(h.entry('a')).toMatchObject({ postId: null, postError: 'Network request failed' });

    // Meanwhile the upload completed: the retry still sends the first body (a key is one request).
    h.deps.update('a', { state: 'PROCESSING', mediaId: 'm-a' });
    await syncGroup(h.deps);

    expect(h.deps.createPost).toHaveBeenCalledTimes(2);
    expect(h.deps.createPost.mock.calls[0]).toEqual(h.deps.createPost.mock.calls[1]);
    // …and the clip goes into its slot right after.
    expect(h.deps.fillPostClip).toHaveBeenCalledWith('post-1', 'slot-a', { mediaId: 'm-a' });
    expect(h.entry('a')).toMatchObject({ postId: 'post-1', attached: true, postError: null });
  });

  it('adds the clips to an existing post instead of creating one', async () => {
    const h = harness(
      [entry('a', 0, { draft: testDraft({ groupKey: 'g', joinPostId: 'post-1' }) })],
      [clip('theirs', 0, 'author'), clip('slot-a', 1)],
    );
    await syncGroup(h.deps);
    expect(h.deps.createPost).not.toHaveBeenCalled();
    expect(h.deps.addPostClips).toHaveBeenCalledWith('post-1', {
      clips: [{ mediaId: null, artistId: null, previewMediaId: null }],
    });
    expect(h.entry('a')).toMatchObject({ clipId: 'slot-a' });
  });

  it('says so when the post is gone, and gives up the slot of a clip the server refused', async () => {
    const h = harness(
      [
        entry('a', 0, { postId: 'post-1', clipId: 'slot-a', state: 'READY', mediaId: 'm-a' }),
        entry('b', 1, { postId: 'post-1', clipId: 'slot-b', state: 'REJECTED', mediaId: 'm-b' }),
      ],
      [],
    );
    h.deps.fillPostClip.mockRejectedValueOnce(new ApiError(404, 'POST_NOT_FOUND', 'No such post'));

    await syncGroup(h.deps);

    expect(h.entry('a').postError).toBe('The post this clip was for is gone.');
    expect(h.deps.removePostClip).toHaveBeenCalledWith('post-1', 'slot-b');
    expect(h.entry('b').clipId).toBeNull();
  });
});

describe('serialiser', () => {
  it('runs one post’s work one call after another, and other posts’ alongside', async () => {
    const run = serialiser();
    const log: string[] = [];
    let release: () => void = () => undefined;
    const first = run('p', () => new Promise<void>((resolve) => (release = resolve)).then(() => {
      log.push('p1');
    }));
    const second = run('p', async () => {
      log.push('p2');
    });
    const other = run('q', async () => {
      log.push('q1');
    });
    await other;
    expect(log).toEqual(['q1']);
    release();
    await Promise.all([first, second]);
    expect(log).toEqual(['q1', 'p1', 'p2']);
  });

  it('keeps going after a failure', async () => {
    const run = serialiser();
    const after = jest.fn(async () => undefined);
    await run('p', async () => {
      throw new Error('boom');
    });
    await run('p', after);
    expect(after).toHaveBeenCalled();
  });
});
