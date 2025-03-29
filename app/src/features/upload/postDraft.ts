import type { CreatePostRequest } from '@/api/generated/model';

import type { UploadEntry } from './types';

export type PostDeps = {
  current: () => UploadEntry;
  update: (patch: Partial<UploadEntry>) => void;
  /** `POST /posts` with the entry's idempotency key; resolves with the created post's id. */
  createPost: (body: CreatePostRequest, idempotencyKey: string) => Promise<{ id: string }>;
};

/**
 * Creates the post for an upload when both halves are there: the user has pressed Post (a draft
 * exists) and the server has the clip (the upload was completed). It is called from both sides, when
 * the upload completes and when Post is pressed, and whichever comes second does the work. Calling it
 * again is harmless: the idempotency key makes the server return the same post.
 */
export async function ensurePost(deps: PostDeps): Promise<void> {
  const entry = deps.current();
  const accepted = entry.state === 'PROCESSING' || entry.state === 'READY';
  if (!entry.draft || entry.postId || !entry.mediaId || !accepted) {
    return;
  }
  try {
    const post = await deps.createPost(
      {
        mediaId: entry.mediaId,
        caption: entry.draft.caption.trim() === '' ? null : entry.draft.caption.trim(),
        gigId: entry.draft.gigId,
        artistIds: entry.draft.artistIds,
      },
      entry.postIdempotencyKey,
    );
    deps.update({ postId: post.id, postError: null });
  } catch (error) {
    deps.update({
      postError: error instanceof Error && error.message ? error.message : 'Could not post.',
    });
  }
}
