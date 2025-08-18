/** One act of a post's clips, for the act bar: its first clip and how many clips are its. */
export type ActSegment = { actId: string; name: string; first: number; count: number };

/**
 * The acts of a post's clips, one chip per act in the order each first appears. The clips stay in the
 * poster's order, so an act's clips may not be next to each other (A1, A2, A1). Clips with
 * no act belong to no segment.
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
      segment.count += 1;
    } else {
      segments.push({ actId: act.id, name: act.name, first: index, count: 1 });
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

/**
 * The act of the clip at `index` (the chip to highlight), or null for a clip with no act. It is the
 * clip's own act, so interleaved acts (A1, A2, A1) highlight right.
 */
export function actAt(
  clips: readonly { act?: { id: string } | null }[],
  index: number,
): string | null {
  return clips[index]?.act?.id ?? null;
}

/** Where tapping an act's chip scrolls to: its first clip (0 for an unknown act). */
export function firstClipOf(segments: readonly ActSegment[], actId: string): number {
  return segments.find((s) => s.actId === actId)?.first ?? 0;
}
