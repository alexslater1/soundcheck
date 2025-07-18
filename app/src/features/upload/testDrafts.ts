import type { PostDraft } from './types';

/** A post draft for tests: a post of one clip, not from a gig, with nothing written. */
export function testDraft(over: Partial<PostDraft> = {}): PostDraft {
  return {
    groupKey: 'group-1',
    order: 0,
    caption: '',
    gigId: null,
    day: null,
    artistIds: [],
    taggedUserIds: [],
    joinPostId: null,
    request: null,
    ...over,
  };
}
