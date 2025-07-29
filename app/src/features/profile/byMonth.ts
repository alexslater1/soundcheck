import type { GigSummary } from '@/api/generated/model';
import { monthTitle, ukDate } from '@/features/calendar/monthGrid';

/** One header of My gigs' all-time list: a UK calendar month and its gigs, in the list's order. */
export type MonthGroup = { key: string; title: string; gigs: GigSummary[] };

/**
 * Groups a list of gigs under month headers ("October 2026"), keeping the list's order (newest first on
 * My gigs), so a page that ends mid-month continues under the same header when the next page arrives.
 */
export function byMonth(gigs: GigSummary[]): MonthGroup[] {
  const groups: MonthGroup[] = [];
  for (const gig of gigs) {
    const key = ukDate(gig.startsAt).slice(0, 7);
    let group = groups.at(-1);
    if (group?.key !== key) {
      const [year, month] = key.split('-').map(Number) as [number, number];
      group = { key, title: monthTitle({ year, month }), gigs: [] };
      groups.push(group);
    }
    group.gigs.push(gig);
  }
  return groups;
}
