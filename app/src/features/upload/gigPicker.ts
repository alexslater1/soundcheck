import { dayWindow, ukDate } from '@/features/calendar/monthGrid';

const DAY = 24 * 60 * 60 * 1000;

/**
 * The window the gig search looks in, fixed when the picker opens. A clip is filmed at a gig
 * that has happened (or is on today), so it looks a year back from now and ends at midnight UK time: a
 * gig of a later day cannot be posted to (the server refuses it too). The suggestions before
 * anything is typed come from the server (`GET /me/gigs/to-post`).
 */
export function searchWindow(now: Date): { from: string; to: string } {
  return {
    from: new Date(now.getTime() - 365 * DAY).toISOString(),
    to: dayWindow(ukDate(now)).to,
  };
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
