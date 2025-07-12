/** What the post page takes over from the feed when a post is opened from it. */
export type Handoff = {
  /** The feed's sound setting: a clip playing with sound keeps its sound on the post page. */
  muted: boolean;
  /** Where the clip was, for players that cannot be shared (the web starts its own at this point). */
  positionMs: number;
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

/** Called by the feed as it opens a post. */
export function recordHandoff(key: string, muted: boolean, now = Date.now()) {
  handoffs.set(key, { muted, positionMs: positions.get(key) ?? 0, at: now });
}

/** What the feed handed over for this clip, if it did so just now. Reading it twice is harmless. */
export function handoffFor(key: string, now = Date.now()): Handoff | undefined {
  const handoff = handoffs.get(key);
  if (!handoff || now - handoff.at > HANDOFF_TTL_MS) {
    handoffs.delete(key);
    return undefined;
  }
  return handoff;
}
