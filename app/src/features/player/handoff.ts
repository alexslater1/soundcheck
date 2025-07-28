/** What the post page takes over from the feed when a post is opened from it. */
export type Handoff = {
  /** Where the clip was, for players that cannot be shared (the web starts its own at this point). */
  positionMs: number;
  /** Which of the post's clips was in view; the post page starts on it. */
  clipKey: string;
  at: number;
};

/** A handoff older than this was not for the page opening now (a back-and-forth, a deep link). */
export const HANDOFF_TTL_MS = 5000;

const handoffs = new Map<string, Handoff>();
const positions = new Map<string, number>();

/** Players report where they are while they play, so a handoff can carry it. */
export function notePosition(key: string, positionMs: number) {
  positions.set(key, positionMs);
}

export function forgetPosition(key: string) {
  positions.delete(key);
}

/**
 * Called by the feed as it opens a post: `key` is the post's, `clipKey` the player's of the clip in
 * view (the post's own key for a post without clips).
 */
export function recordHandoff(key: string, clipKey = key, now = Date.now()) {
  handoffs.set(key, { positionMs: positions.get(clipKey) ?? 0, clipKey, at: now });
}

/** What the feed handed over for this post, if it did so just now. Reading it twice is harmless. */
export function handoffFor(key: string, now = Date.now()): Handoff | undefined {
  const handoff = handoffs.get(key);
  if (!handoff || now - handoff.at > HANDOFF_TTL_MS) {
    handoffs.delete(key);
    return undefined;
  }
  return handoff;
}
