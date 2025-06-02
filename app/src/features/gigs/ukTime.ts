/**
 * UK wall-clock maths. Gigs are UK events, so calendar days are worked out on the UK clock,
 * wherever the phone is. (These helpers began as the Gigs tab's date chips, which went with the List
 * view; the calendar still needs them.)
 */
export const UK_TIME_ZONE = 'Europe/London';

type WallTime = { year: number; month: number; day: number; hour: number; minute: number };

/** The wall-clock reading of `date` in `timeZone`. */
export function wallTime(date: Date, timeZone: string): WallTime {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone,
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: 'numeric',
    hourCycle: 'h23',
  }).formatToParts(date);
  const get = (type: string) => Number(parts.find((p) => p.type === type)?.value ?? '0');
  return {
    year: get('year'),
    month: get('month'),
    day: get('day'),
    hour: get('hour'),
    minute: get('minute'),
  };
}

/**
 * The instant at which clocks in `timeZone` read the given wall time. `day` may overflow the month
 * (`day + 1` on the 31st is the 1st): `Date.UTC` normalises it.
 */
export function instantAt(
  timeZone: string,
  year: number,
  month: number,
  day: number,
  hour: number,
  minute = 0,
): Date {
  const asIfUtc = Date.UTC(year, month - 1, day, hour, minute);
  // How far the zone is ahead of UTC around that moment (0 or +1 h for the UK).
  const w = wallTime(new Date(asIfUtc), timeZone);
  const offset = Date.UTC(w.year, w.month - 1, w.day, w.hour, w.minute) - asIfUtc;
  return new Date(asIfUtc - offset);
}
