import type { Post } from '@/api/generated/model';

import { actChoices, canRemove, changes, initialState, moveClip, nextAct } from './editPlan';

const user = (id: string) => ({ id, handle: id, displayName: id });
const artist = (id: string) => ({ id, name: id }) as never;
const playback = { status: 'READY', thumbUrl: 'https://t/x.webp' } as never;
const clip = (id: string, act: string | null) => ({
  id,
  position: 0,
  act: act ? artist(act) : null,
  owner: user('me'),
  status: 'READY',
  playback,
});

const post = {
  id: 'p1',
  caption: 'Hello',
  gig: { headliner: artist('keo'), support: [artist('cards')] },
  clips: [clip('c1', 'cards'), clip('c2', 'keo'), clip('c3', 'other')],
  withUsers: [user('mate')],
} as unknown as Post;

describe('editPlan', () => {
  it('starts from the post', () => {
    const state = initialState(post);
    expect(state.caption).toBe('Hello');
    expect(state.clips.map((c) => c.id)).toEqual(['c1', 'c2', 'c3']);
    expect(state.clips[0]!.thumbUrl).toBe('https://t/x.webp');
    expect(state.tagged.map((u) => u.id)).toEqual(['mate']);
  });

  it('moves a clip one place and leaves the ends alone', () => {
    const clips = initialState(post).clips;
    expect(moveClip(clips, 0, 1).map((c) => c.id)).toEqual(['c2', 'c1', 'c3']);
    expect(moveClip(clips, 2, -1).map((c) => c.id)).toEqual(['c1', 'c3', 'c2']);
    expect(moveClip(clips, 0, -1)).toBe(clips);
    expect(moveClip(clips, 2, 1)).toBe(clips);
  });

  it('never removes the last clip', () => {
    const clips = initialState(post).clips;
    expect(canRemove(clips)).toBe(true);
    expect(canRemove(clips.slice(0, 1))).toBe(false);
  });

  it('offers the bill, then other acts in use, and cycles through no act', () => {
    const choices = actChoices(post, initialState(post).clips);
    expect(choices.map((a) => a.id)).toEqual(['keo', 'cards', 'other']);
    expect(nextAct(choices, null)?.id).toBe('keo');
    expect(nextAct(choices, choices[0]!)?.id).toBe('cards');
    expect(nextAct(choices, choices[2]!)).toBeNull();
  });

  it('sends only what changed', () => {
    const before = initialState(post);
    expect(changes(before, before)).toEqual({});
    expect(changes(before, { ...before, caption: '  Hello ' })).toEqual({});
    expect(changes(before, { ...before, caption: '' })).toEqual({ caption: '' });
    const reordered = { ...before, clips: moveClip(before.clips, 0, 1) };
    expect(changes(before, reordered).clips).toEqual([
      { id: 'c2', artistId: 'keo' },
      { id: 'c1', artistId: 'cards' },
      { id: 'c3', artistId: 'other' },
    ]);
    expect(changes(before, { ...before, tagged: [] })).toEqual({ taggedUserIds: [] });
  });
});
