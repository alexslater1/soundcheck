import type { ArtistSummary, EditPostRequest, Post, UserSummary } from '@/api/generated/model';

/** One clip in the edit screen: which clip, its act (null: no act), and whose it is. */
export type EditClip = {
  id: string;
  act: ArtistSummary | null;
  owner: UserSummary;
  thumbUrl: string | null;
  status: string;
};

/** What the edit screen holds: the caption, the clips in order, and the tagged people. */
export type EditState = { caption: string; clips: EditClip[]; tagged: UserSummary[] };

export function initialState(post: Post): EditState {
  return {
    caption: post.caption ?? '',
    clips: post.clips.map((clip) => ({
      id: clip.id,
      act: clip.act ?? null,
      owner: clip.owner,
      thumbUrl: clip.playback.thumbUrl ?? clip.previewUrl ?? null,
      status: clip.status,
    })),
    tagged: post.withUsers,
  };
}

/** The clip at `index` moves one place up (-1) or down (+1); at an end it stays where it is. */
export function moveClip(clips: EditClip[], index: number, by: -1 | 1): EditClip[] {
  const to = index + by;
  if (to < 0 || to >= clips.length) {
    return clips;
  }
  const next = [...clips];
  const [moved] = next.splice(index, 1);
  next.splice(to, 0, moved!);
  return next;
}

/** A clip can be removed while it is not the last one: a post keeps at least one (delete it instead). */
export function canRemove(clips: EditClip[]): boolean {
  return clips.length > 1;
}

/** The acts a clip can be tagged with: the gig's bill (headliner first), then any other act already used. */
export function actChoices(post: Post, clips: EditClip[]): ArtistSummary[] {
  const bill = post.gig ? [post.gig.headliner, ...post.gig.support] : [];
  const seen = new Set(bill.map((artist) => artist.id));
  const others: ArtistSummary[] = [];
  for (const clip of clips) {
    if (clip.act && !seen.has(clip.act.id)) {
      seen.add(clip.act.id);
      others.push(clip.act);
    }
  }
  return [...bill, ...others];
}

/** The next act for a clip when its chip is tapped: through the choices, then "no act", then round again. */
export function nextAct(
  choices: ArtistSummary[],
  current: ArtistSummary | null,
): ArtistSummary | null {
  if (current === null) {
    return choices[0] ?? null;
  }
  const at = choices.findIndex((artist) => artist.id === current.id);
  return at < 0 || at === choices.length - 1 ? null : choices[at + 1]!;
}

/**
 * The PATCH body: only what changed. The clips are sent in full whenever their order, acts or number
 * changed (clips left out are removed by the server).
 */
export function changes(before: EditState, after: EditState): EditPostRequest {
  const body: EditPostRequest = {};
  if (after.caption.trim() !== before.caption.trim()) {
    body.caption = after.caption.trim();
  }
  const placement = (state: EditState) =>
    state.clips.map((c) => `${c.id}:${c.act?.id ?? ''}`).join(',');
  if (placement(after) !== placement(before)) {
    body.clips = after.clips.map((clip) => ({ id: clip.id, artistId: clip.act?.id ?? null }));
  }
  const people = (state: EditState) =>
    state.tagged
      .map((u) => u.id)
      .sort()
      .join(',');
  if (people(after) !== people(before)) {
    body.taggedUserIds = after.tagged.map((user) => user.id);
  }
  return body;
}
