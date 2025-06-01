import type { GigSummary } from '@/api/generated/model';
import { ukDate } from '@/features/calendar/monthGrid';

/** Gigs per UK day, in start order within each day. */
export function byDay(gigs: GigSummary[]): Record<string, GigSummary[]> {
  const days: Record<string, GigSummary[]> = {};
  for (const gig of [...gigs].sort((a, b) => a.startsAt.localeCompare(b.startsAt))) {
    (days[ukDate(gig.startsAt)] ??= []).push(gig);
  }
  return days;
}
