/**
 * The Gigs tab's date chips as pure date maths. Gigs are UK events, so "tonight" and "this
 * weekend" are worked out on the UK clock, wherever the phone is; the result is a pair of instants for
 * the API's `from`/`to` (a half-open window: from ≤ startsAt < to).
 */
export type DateChip = 'tonight' | 'weekend' | 'week' | 'month';

export const DATE_CHIPS: readonly { key: DateChip; label: string }[] = [
  { key: 'tonight', label: 'Tonight' },
  { key: 'weekend', label: 'This weekend' },
  { key: 'week', label: 'Next 7 days' },
  { key: 'month', label: 'Next 30 days' },
];

export const UK_TIME_ZONE = 'Europe/London';

/** A night out runs past midnight: "tonight" ends at 04:00, and the weekend starts on Friday at 17:00. */
const NIGHT_ENDS_HOUR = 4;
const WEEKEND_STARTS_HOUR = 17;
const DAY_MS = 24 * 60 * 60 * 1000;

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

/** Day of the week of a calendar date: 0 = Sunday … 6 = Saturday. */
function weekday(year: number, month: number, day: number): number {
  return new Date(Date.UTC(year, month - 1, day)).getUTCDay();
}

export function chipRange(
  chip: DateChip,
  now: Date,
  timeZone: string = UK_TIME_ZONE,
): { from: Date; to: Date } {
  const local = wallTime(now, timeZone);
  switch (chip) {
    case 'tonight': {
      // Before 04:00 it is still last night's night out; otherwise tonight ends at 04:00 tomorrow.
      const endDay = local.hour < NIGHT_ENDS_HOUR ? local.day : local.day + 1;
      return {
        from: now,
        to: instantAt(timeZone, local.year, local.month, endDay, NIGHT_ENDS_HOUR),
      };
    }
    case 'weekend': {
      const today = weekday(local.year, local.month, local.day);
      // Days from today to the Friday that opens this weekend: negative on Saturday and Sunday.
      const toFriday = today === 6 ? -1 : today === 0 ? -2 : 5 - today;
      const start = instantAt(
        timeZone,
        local.year,
        local.month,
        local.day + toFriday,
        WEEKEND_STARTS_HOUR,
      );
      const end = instantAt(timeZone, local.year, local.month, local.day + toFriday + 3, 0);
      return { from: now > start ? now : start, to: end };
    }
    case 'week':
      return { from: now, to: new Date(now.getTime() + 7 * DAY_MS) };
    case 'month':
      return { from: now, to: new Date(now.getTime() + 30 * DAY_MS) };
  }
}
