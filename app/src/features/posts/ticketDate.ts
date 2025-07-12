/**
 * A gig's date as a ticket stub shows it: "SEP", "26", "2026" (every card says when the
 * gig was, in full, because a clip can be posted long after the night). UK time by default, as on
 * the gig pages.
 */
export function ticketDate(
  iso: string,
  timeZone = 'Europe/London',
): { month: string; day: string; year: string } {
  const parts = new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    timeZone,
  }).formatToParts(new Date(iso));
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((p) => p.type === type)?.value ?? '';
  // Three letters on a stub: en-GB says "Sept" on some engines and "Sep" on others.
  return { month: part('month').slice(0, 3).toUpperCase(), day: part('day'), year: part('year') };
}
