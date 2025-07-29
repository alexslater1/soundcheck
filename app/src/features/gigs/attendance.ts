import type { AttendanceCounts, GigDetail } from '@/api/generated/model';

import { UK_TIME_ZONE, wallTime } from './ukTime';

export type AttendanceStatus = 'INTERESTED' | 'GOING' | 'WENT';

export const ATTENDANCE_OPTIONS: readonly { status: AttendanceStatus; label: string }[] = [
  { status: 'INTERESTED', label: 'Interested' },
  { status: 'GOING', label: 'Going' },
  { status: 'WENT', label: 'Went' },
];

/**
 * Where a gig stands on the UK calendar (the server's `GigTime` decides the same way):
 * `upcoming` before its day, `today` on it, `over` once its day has ended.
 */
export type GigPhase = 'upcoming' | 'today' | 'over';

export function gigPhase(startsAt: string, now: Date = new Date()): GigPhase {
  const gig = wallTime(new Date(startsAt), UK_TIME_ZONE);
  const today = wallTime(now, UK_TIME_ZONE);
  const gigDay = Date.UTC(gig.year, gig.month - 1, gig.day);
  const todayDay = Date.UTC(today.year, today.month - 1, today.day);
  return gigDay > todayDay ? 'upcoming' : gigDay === todayDay ? 'today' : 'over';
}

/** Whether the gig's day has come: Went, posting from it and its clips all start then. */
export const dayHasCome = (startsAt: string, now: Date = new Date()) =>
  gigPhase(startsAt, now) !== 'upcoming';

/**
 * The statuses a fan may choose (mirrored from the server): Interested and Going until the gig's
 * day ends, Went from its day on, none on a cancelled gig.
 */
export function attendanceOptions(
  phase: GigPhase,
  gigStatus: string,
): readonly { status: AttendanceStatus; label: string }[] {
  if (gigStatus === 'CANCELLED') return [];
  return ATTENDANCE_OPTIONS.filter((option) =>
    phase === 'upcoming'
      ? option.status !== 'WENT'
      : phase === 'over'
        ? option.status === 'WENT'
        : true,
  );
}

/**
 * The counts line: before the gig who is going and interested, after it only who went (a Going whose gig
 * is over is counted as went by the server), and on the day all three.
 */
export function countsLine(counts: AttendanceCounts, phase: GigPhase): string {
  if (phase === 'over') return `${counts.went} went`;
  const before = `${counts.going} going · ${counts.interested} interested`;
  return phase === 'today' ? `${before} · ${counts.went} went` : before;
}

const COUNT_KEY = { INTERESTED: 'interested', GOING: 'going', WENT: 'went' } as const;

/**
 * What the gig detail will look like once the server has applied a change of the viewer's attendance
 * (`null` clears it). Used for the optimistic update: the screen shows this at once, and puts the old
 * value back if the request fails.
 */
export function applyAttendance(detail: GigDetail, next: AttendanceStatus | null): GigDetail {
  const previous = (detail.gig.myAttendance ?? null) as AttendanceStatus | null;
  if (previous === next) {
    // Confirming a Went the server assumed from a past Going changes no count.
    return detail.gig.wentUnconfirmed
      ? { ...detail, gig: { ...detail.gig, wentUnconfirmed: false } }
      : detail;
  }
  const counts = { ...detail.attendanceCounts };
  if (previous) counts[COUNT_KEY[previous]] = Math.max(0, counts[COUNT_KEY[previous]] - 1);
  if (next) counts[COUNT_KEY[next]] += 1;
  return {
    ...detail,
    gig: { ...detail.gig, myAttendance: next, wentUnconfirmed: false },
    attendanceCounts: counts,
  };
}

/**
 * What tapping a status means: the selected one again clears it, except a Went the server assumed from
 * a past Going, which tapping confirms.
 */
export function nextAttendance(
  current: { myAttendance?: string | null; wentUnconfirmed?: boolean },
  tapped: AttendanceStatus,
): AttendanceStatus | null {
  if (current.myAttendance !== tapped) return tapped;
  return tapped === 'WENT' && current.wentUnconfirmed ? 'WENT' : null;
}
