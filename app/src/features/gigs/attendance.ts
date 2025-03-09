import type { GigDetail } from '@/api/generated/model';

export type AttendanceStatus = 'INTERESTED' | 'GOING' | 'WENT';

export const ATTENDANCE_OPTIONS: readonly { status: AttendanceStatus; label: string }[] = [
  { status: 'INTERESTED', label: 'Interested' },
  { status: 'GOING', label: 'Going' },
  { status: 'WENT', label: 'Went' },
];

const COUNT_KEY = { INTERESTED: 'interested', GOING: 'going', WENT: 'went' } as const;

/**
 * What the gig detail will look like once the server has applied a change of the viewer's attendance
 * (`null` clears it). Used for the optimistic update: the screen shows this at once, and puts the old
 * value back if the request fails.
 */
export function applyAttendance(detail: GigDetail, next: AttendanceStatus | null): GigDetail {
  const previous = (detail.gig.myAttendance ?? null) as AttendanceStatus | null;
  if (previous === next) return detail;
  const counts = { ...detail.attendanceCounts };
  if (previous) counts[COUNT_KEY[previous]] = Math.max(0, counts[COUNT_KEY[previous]] - 1);
  if (next) counts[COUNT_KEY[next]] += 1;
  return { ...detail, gig: { ...detail.gig, myAttendance: next }, attendanceCounts: counts };
}
