import type { GigSummary } from '@/api/generated/model';

/** An act chosen for a clip: just enough to show its name. */
export type TaggedArtist = { id: string; name: string };

/** A gig's bill, headliner first, less the acts the fan missed: the acts a clip from that gig can be of. */
export function billOf(gig: GigSummary): { id: string; name: string }[] {
  const all = [gig.headliner, ...gig.support].map((artist) => ({
    id: artist.id,
    name: artist.name,
  }));
  // The acts the fan saw: the ones they unticked are not offered for a clip.
  const missed = new Set(gig.myActsMissed ?? []);
  const seen = all.filter((artist) => !missed.has(artist.id));
  return seen.length > 0 ? seen : all;
}
