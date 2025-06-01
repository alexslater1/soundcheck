import type { GigSummary } from '@/api/generated/model';

/** A gig's bill, headliner first: the artists a clip from that gig is tagged with unless the user unticks them. */
export function billOf(gig: GigSummary): { id: string; name: string }[] {
  return [gig.headliner, ...gig.support].map((artist) => ({ id: artist.id, name: artist.name }));
}
