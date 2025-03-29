import type { GigSummary } from '@/api/generated/model';

/** The gigs offered on the compose form: ones the user went to first, then ones nearby, without repeats. */
export function gigChoices(attended: GigSummary[], nearby: GigSummary[], limit = 8): GigSummary[] {
  const seen = new Set<string>();
  const choices: GigSummary[] = [];
  for (const gig of [...attended, ...nearby]) {
    if (!seen.has(gig.id)) {
      seen.add(gig.id);
      choices.push(gig);
    }
  }
  return choices.slice(0, limit);
}

/** A gig's bill, headliner first: the artists a clip from that gig is tagged with unless the user unticks them. */
export function billOf(gig: GigSummary): { id: string; name: string }[] {
  return [gig.headliner, ...gig.support].map((artist) => ({ id: artist.id, name: artist.name }));
}
