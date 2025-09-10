import type { GigMergeCount, GigMergePreview, GigSummary } from '@/api/generated/model';
import { formatGigDate } from '@/features/gigs/format';

/** What each count key means, in words (the keys come from the server: GigMergeCount.what). */
const LABELS: Record<string, [one: string, many: string]> = {
  attendance: ['fan mark (Interested, Going, Went)', 'fan marks (Interested, Going, Went)'],
  acts: ['act on the bill', 'acts on the bill'],
  listings: ['listing', 'listings'],
  edits: ['edit in the history', 'edits in the history'],
  posts: ['post', 'posts'],
  gigComments: ['gig comment', 'gig comments'],
  alertsSent: ['new-gig alert record', 'new-gig alert records'],
  matchReviews: ['match review', 'match reviews'],
};

/** "3 posts", "1 listing"; an unknown key is shown as it is. */
export function countLabel({ what, count }: GigMergeCount): string {
  const words = LABELS[what];
  if (!words) return `${count} ${what}`;
  return `${count} ${count === 1 ? words[0] : words[1]}`;
}

/** "Keo · Fri 17 Oct 2026 · Band on the Wall": one line that tells two gigs apart. */
export function gigLine(gig: GigSummary): string {
  return `${gig.title} · ${formatGigDate(gig.startsAt)} · ${gig.venue.name}`;
}

/** "Keo, Opener" (headliner first). */
export function billLine(gig: GigSummary): string {
  return [gig.headliner, ...gig.support].map((a) => a.name).join(', ');
}

/**
 * Warnings that don't refuse a merge but should make the operator look twice: the server only refuses
 * conflicts, so two different nights can be merged if the operator means it.
 */
export function mergeWarnings(preview: GigMergePreview): string[] {
  const warnings: string[] = [];
  if (!preview.sameDay) warnings.push('The two gigs are on different days.');
  if (!preview.sameVenue) warnings.push('The two gigs are at different venues.');
  return warnings;
}

/**
 * The confirm dialog's lines (they say exactly what will change). Only kinds with rows are listed;
 * the last two lines are what happens to the duplicate itself.
 */
export function mergeChanges(preview: GigMergePreview): string[] {
  const into = gigLine(preview.into.gig);
  const moving = preview.moving
    .filter((m) => m.count > 0)
    .map((m) => `Move ${countLabel(m)} to ${into}`);
  return [
    ...(moving.length > 0
      ? moving
      : [`Move nothing: the duplicate has no marks, posts or listings`]),
    `Delete ${gigLine(preview.from.gig)}`,
    'Send its old link to the gig that stays',
  ];
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** True for a pasted gig id (the picker then looks it up instead of searching). */
export function isGigId(text: string): boolean {
  return UUID.test(text.trim());
}
