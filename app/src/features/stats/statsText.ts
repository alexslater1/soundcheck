/** Display helpers for the stats section: pure, so they are unit-tested without rendering. */

const DAY = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  timeZone: 'Europe/London',
});

/** "1 Mar 2025": a gig's date as it was in the UK. */
export function formatStatDate(iso: string): string {
  return DAY.format(new Date(iso));
}

/**
 * The line under an act or venue: the date for a single gig, or for several on one day (two
 * bills in one night; the badge beside it says how many), otherwise the first and the last date as a
 * range, "25 Sept 2026 – 2 Oct 2026".
 */
export function seenLine(times: number, first: string, last: string): string {
  const from = formatStatDate(first);
  const to = formatStatDate(last);
  if (times <= 1 || from === to) return from;
  return `${from} – ${to}`;
}

/** "1 gig", "3 gigs"; the same for any noun with a regular plural. */
export function count(n: number, noun: string): string {
  return `${n} ${noun}${n === 1 ? '' : 's'}`;
}

export type YearComparison = {
  thisYear: number;
  lastYear: number;
  thisGigs: number;
  lastGigs: number;
};

/**
 * This year's gigs against last year's. A year without gigs is missing from the server's list, so it is
 * read as zero here; null when neither year has any (nothing worth comparing).
 */
export function compareYears(
  currentYear: number,
  years: readonly { year: number; gigs: number }[],
): YearComparison | null {
  const gigsIn = (year: number) => years.find((y) => y.year === year)?.gigs ?? 0;
  const comparison = {
    thisYear: currentYear,
    lastYear: currentYear - 1,
    thisGigs: gigsIn(currentYear),
    lastGigs: gigsIn(currentYear - 1),
  };
  return comparison.thisGigs === 0 && comparison.lastGigs === 0 ? null : comparison;
}

/** "8 more than 2025", "3 fewer than 2025", "Same as 2025". */
export function yearDelta({ thisGigs, lastGigs, lastYear }: YearComparison): string {
  const delta = thisGigs - lastGigs;
  if (delta === 0) return `Same as ${lastYear}`;
  return delta > 0 ? `${delta} more than ${lastYear}` : `${-delta} fewer than ${lastYear}`;
}
