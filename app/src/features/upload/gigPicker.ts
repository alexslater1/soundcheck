import type { GigSummary } from '@/api/generated/model';
import { dayWindow, ukDate } from '@/features/calendar/monthGrid';

const DAY = 24 * 60 * 60 * 1000;

/**
 * The windows the clip picker asks for, fixed when the picker opens. A clip is filmed at a
 * gig that has happened (or is on today), so everything looks backwards from now and ends at midnight
 * UK time: a gig of a later day cannot be posted to (H43; the server refuses it too).
 * - mine: gigs the user marked in the last two weeks;
 * - recent: gigs near home from the last three days;
 * - search: a year back (a clip posted long after).
 */
export function pickerWindows(
  now: Date,
): Record<'mine' | 'recent' | 'search', { from: string; to: string }> {
  const at = (offset: number) => new Date(now.getTime() + offset).toISOString();
  const endOfToday = dayWindow(ukDate(now)).to;
  return {
    mine: { from: at(-14 * DAY), to: endOfToday },
    recent: { from: at(-3 * DAY), to: endOfToday },
    search: { from: at(-365 * DAY), to: endOfToday },
  };
}

/**
 * The suggestions shown before anything is typed: the user's own gigs first (most recent first), then
 * recent gigs near home, each gig once.
 */
export function suggestedGigs(mine: GigSummary[], recent: GigSummary[], limit = 8): GigSummary[] {
  const newestFirst = (a: GigSummary, b: GigSummary) => b.startsAt.localeCompare(a.startsAt);
  const seen = new Set<string>();
  const out: GigSummary[] = [];
  for (const gig of [...[...mine].sort(newestFirst), ...[...recent].sort(newestFirst)]) {
    if (!seen.has(gig.id)) {
      seen.add(gig.id);
      out.push(gig);
    }
  }
  return out.slice(0, limit);
}

/** "19:30" or "9:05" → "19:30" / "09:05"; anything else (including "") → null. */
export function parseTime(text: string): string | null {
  const match = /^(\d{1,2}):(\d{2})$/.exec(text.trim());
  if (!match) {
    return null;
  }
  const hours = Number(match[1]);
  const minutes = Number(match[2]);
  if (hours > 23 || minutes > 59) {
    return null;
  }
  return `${String(hours).padStart(2, '0')}:${match[2]}`;
}

/** "Post", "Post 3 clips". */
export function postLabel(clips: number): string {
  return clips > 1 ? `Post ${clips} clips` : 'Post';
}

/** An act on the add-a-gig form: one the catalog knows (with its id), or a new name. */
export type ActChoice = { id: string | null; name: string };

/** Adds an act unless the same one (by id, or by name ignoring case) is already listed. */
export function addAct(acts: ActChoice[], act: ActChoice): ActChoice[] {
  const name = act.name.trim().replace(/\s+/g, ' ');
  if (name === '') {
    return acts;
  }
  const duplicate = acts.some(
    (a) => (act.id !== null && a.id === act.id) || a.name.toLowerCase() === name.toLowerCase(),
  );
  return duplicate ? acts : [...acts, { id: act.id, name }];
}
