/** One act's run of clips in a post: where its clips start and end (the act bar). */
export type ActSegment = { actId: string; name: string; first: number; last: number };

/**
 * The acts of a post's clips, in clip order, one segment per act. The server sends each act's clips
 * together; if an act's clips were ever split, its segment spans from its first clip to its last. Clips
 * with no act belong to no segment.
 */
export function actSegments(
  clips: readonly { act?: { id: string; name: string } | null }[],
): ActSegment[] {
  const segments: ActSegment[] = [];
  clips.forEach((clip, index) => {
    const act = clip.act;
    if (!act) {
      return;
    }
    const segment = segments.find((s) => s.actId === act.id);
    if (segment) {
      segment.last = index;
    } else {
      segments.push({ actId: act.id, name: act.name, first: index, last: index });
    }
  });
  return segments;
}

/**
 * The bar shows whenever a clip has an act, one act included (each clip should say
 * whose set it is; it was hidden for a single act). Only a post with no act on any clip has none.
 */
export function showsActBar(segments: readonly ActSegment[]): boolean {
  return segments.length >= 1;
}

/** The act of the clip at `index` (the chip to highlight), or null for a clip with no act. */
export function actAt(segments: readonly ActSegment[], index: number): string | null {
  return segments.find((s) => index >= s.first && index <= s.last)?.actId ?? null;
}

/** Where tapping an act's chip scrolls to: its first clip (0 for an unknown act). */
export function firstClipOf(segments: readonly ActSegment[], actId: string): number {
  return segments.find((s) => s.actId === actId)?.first ?? 0;
}
