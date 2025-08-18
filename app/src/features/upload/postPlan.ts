import type { GigSummary } from '@/api/generated/model';
import { ukDate } from '@/features/calendar/monthGrid';

/** Clips one person may put in a post per act; clips with no act are one more group. */
export const MAX_CLIPS_PER_ACT = 10;

/** A festival's later days a post can be for, after its first (the server's limit). */
export const MAX_DAYS_AFTER = 3;

/** The longest song name a clip can be tagged with (the server's limit). */
export const MAX_SONG = 120;

/** A clip on the compose form with the act chosen for it (null: no act), and the song typed for it. */
export type ClipAct = { localId: string; artistId: string | null; song?: string | null };

/** The acts (null: "no act") with more clips than a post may hold. */
export function actsOverLimit(clips: ClipAct[]): (string | null)[] {
  const counts = new Map<string | null, number>();
  clips.forEach((clip) => counts.set(clip.artistId, (counts.get(clip.artistId) ?? 0) + 1));
  return [...counts.entries()].filter(([, n]) => n > MAX_CLIPS_PER_ACT).map(([act]) => act);
}

/**
 * Whether a gig looks like it lasts more than a day (a festival): its title says so, or its bill is
 * long. The catalog has no end dates, so this decides when Create asks "Which day?".
 */
export function looksMultiDay(gig: GigSummary): boolean {
  return /festival|fest\b|weekender|all-?dayer/i.test(gig.title) || 1 + gig.support.length >= 6;
}

function addDays(date: string, days: number): string {
  const [year, month, day] = date.split('-').map(Number) as [number, number, number];
  const next = new Date(Date.UTC(year, month - 1, day + days));
  return next.toISOString().slice(0, 10);
}

/**
 * The UK days a post from the gig can be for: its first day up to {@link MAX_DAYS_AFTER} more, never
 * past today. Empty for a gig that does not look multi-day (its posts are for its own day).
 */
export function festivalDays(gig: GigSummary, now: Date): string[] {
  if (!looksMultiDay(gig)) {
    return [];
  }
  const first = ukDate(gig.startsAt);
  const today = ukDate(now);
  const days: string[] = [];
  for (let i = 0; i <= MAX_DAYS_AFTER; i++) {
    const day = addDays(first, i);
    if (day > today) {
      break;
    }
    days.push(day);
  }
  return days;
}

/** What still stops Post, in words; null when it can be pressed. */
export function postHint(state: {
  gigChosen: boolean;
  clips: number;
  overLimit: boolean;
  joining?: boolean;
}): string | null {
  const needsGig = !state.gigChosen && !state.joining;
  if (needsGig && state.clips === 0) {
    return 'Choose the gig and add your clips to post.';
  }
  if (needsGig) {
    return 'Choose the gig (or say these clips are not from one) to post.';
  }
  if (state.clips === 0) {
    return 'Add at least one clip to post.';
  }
  if (state.overLimit) {
    return `A post can hold ${MAX_CLIPS_PER_ACT} of your clips of one act.`;
  }
  return null;
}
