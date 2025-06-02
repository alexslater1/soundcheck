import { instantAt, UK_TIME_ZONE, wallTime } from '@/features/gigs/ukTime';

/** A calendar month: `month` is 1–12. */
export type Month = { year: number; month: number };

/** One square of the month grid. `date` is `YYYY-MM-DD`; days of the neighbouring months fill the edges. */
export type DayCell = { date: string; day: number; inMonth: boolean };

const pad = (n: number) => String(n).padStart(2, '0');

/** `YYYY-MM-DD` for a calendar date (any day overflow is normalised: day 0 is the previous month's last). */
export function isoDate(year: number, month: number, day: number): string {
  const d = new Date(Date.UTC(year, month - 1, day));
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
}

/** The UK calendar day an instant falls on, as `YYYY-MM-DD` (a gig at 00:30 belongs to the new day). */
export function ukDate(instant: Date | string): string {
  const w = wallTime(typeof instant === 'string' ? new Date(instant) : instant, UK_TIME_ZONE);
  return isoDate(w.year, w.month, w.day);
}

/** The month that contains an instant, on the UK calendar. */
export function monthOf(instant: Date): Month {
  const w = wallTime(instant, UK_TIME_ZONE);
  return { year: w.year, month: w.month };
}

export function addMonths({ year, month }: Month, delta: number): Month {
  const index = year * 12 + (month - 1) + delta;
  return { year: Math.floor(index / 12), month: (index % 12) + 1 };
}

/**
 * The weeks of a month as rows of seven days, Monday first (the UK convention), padded with the end of
 * the previous month and the start of the next so every row is full. Four to six rows.
 */
export function monthGrid({ year, month }: Month): DayCell[][] {
  const first = new Date(Date.UTC(year, month - 1, 1));
  const daysInMonth = new Date(Date.UTC(year, month, 0)).getUTCDate();
  // getUTCDay: 0 = Sunday. Days to step back to reach Monday.
  const lead = (first.getUTCDay() + 6) % 7;
  const cells = Math.ceil((lead + daysInMonth) / 7) * 7;
  const weeks: DayCell[][] = [];
  for (let i = 0; i < cells; i++) {
    const d = new Date(Date.UTC(year, month - 1, 1 - lead + i));
    const cell = {
      date: isoDate(d.getUTCFullYear(), d.getUTCMonth() + 1, d.getUTCDate()),
      day: d.getUTCDate(),
      inMonth: d.getUTCMonth() === month - 1,
    };
    if (i % 7 === 0) {
      weeks.push([]);
    }
    weeks[weeks.length - 1]!.push(cell);
  }
  return weeks;
}

/** The instants that bound a month on the UK clock, for the API's `from`/`to` (half-open). */
export function monthWindow({ year, month }: Month): { from: string; to: string } {
  const next = addMonths({ year, month }, 1);
  return {
    from: instantAt(UK_TIME_ZONE, year, month, 1, 0).toISOString(),
    to: instantAt(UK_TIME_ZONE, next.year, next.month, 1, 0).toISOString(),
  };
}

/** The instants that bound one UK day. */
export function dayWindow(date: string): { from: string; to: string } {
  const [year, month, day] = date.split('-').map(Number) as [number, number, number];
  return {
    from: instantAt(UK_TIME_ZONE, year, month, day, 0).toISOString(),
    to: instantAt(UK_TIME_ZONE, year, month, day + 1, 0).toISOString(),
  };
}

const MONTH_NAMES = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
];

export function monthTitle({ year, month }: Month): string {
  return `${MONTH_NAMES[month - 1]} ${year}`;
}
