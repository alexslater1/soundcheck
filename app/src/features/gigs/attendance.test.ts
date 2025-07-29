import type { GigDetail } from '@/api/generated/model';

import {
  applyAttendance,
  attendanceOptions,
  countsLine,
  gigPhase,
  nextAttendance,
} from './attendance';

const detail = (my: GigDetail['gig']['myAttendance'], going: number, interested = 0): GigDetail =>
  ({
    gig: { id: 'g1', title: 'A gig', myAttendance: my },
    attendanceCounts: { interested, going, went: 0 },
    lineup: [],
    sources: [],
    postsCount: 0,
    privatePostsCount: 0,
  }) as unknown as GigDetail;

describe('applyAttendance (optimistic update)', () => {
  it('adds the viewer to a status', () => {
    const next = applyAttendance(detail(null, 3), 'GOING');
    expect(next.gig.myAttendance).toBe('GOING');
    expect(next.attendanceCounts).toEqual({ interested: 0, going: 4, went: 0 });
  });

  it('moves the viewer between statuses', () => {
    const next = applyAttendance(detail('GOING', 4), 'INTERESTED');
    expect(next.attendanceCounts).toEqual({ interested: 1, going: 3, went: 0 });
  });

  it('clears and never goes below zero', () => {
    const next = applyAttendance(detail('GOING', 0), null);
    expect(next.gig.myAttendance).toBeNull();
    expect(next.attendanceCounts.going).toBe(0);
  });

  it('is a no-op for the same status and does not mutate its input', () => {
    const before = detail('GOING', 2);
    expect(applyAttendance(before, 'GOING')).toBe(before);
    applyAttendance(before, null);
    expect(before.attendanceCounts.going).toBe(2);
  });
});

describe("gigPhase (the gig's UK day)", () => {
  // 2026-07-01 in summer time: UK midnight is 23:00 UTC the day before.
  const now = new Date('2026-07-01T12:00:00Z');

  it('is today from UK midnight to UK midnight, whatever the hour', () => {
    expect(gigPhase('2026-06-30T23:00:00Z', now)).toBe('today');
    expect(gigPhase('2026-07-01T22:59:00Z', now)).toBe('today');
  });

  it('is upcoming from the next UK day and over before today', () => {
    expect(gigPhase('2026-07-01T23:00:00Z', now)).toBe('upcoming');
    expect(gigPhase('2026-06-30T22:59:00Z', now)).toBe('over');
  });

  it('works on the UK clock in winter too', () => {
    const winter = new Date('2026-12-01T12:00:00Z');
    // In winter the UK is on UTC: 23:30 UTC is still the same day, midnight UTC is the next.
    expect(gigPhase('2026-12-01T23:30:00Z', winter)).toBe('today');
    expect(gigPhase('2026-12-02T00:00:00Z', winter)).toBe('upcoming');
    expect(gigPhase('2026-11-30T23:59:00Z', winter)).toBe('over');
  });
});

describe('attendance rules', () => {
  const statuses = (phase: 'upcoming' | 'today' | 'over', status = 'SCHEDULED') =>
    attendanceOptions(phase, status).map((option) => option.status);

  it("offers what the gig's day allows", () => {
    expect(statuses('upcoming')).toEqual(['INTERESTED', 'GOING']);
    expect(statuses('today')).toEqual(['INTERESTED', 'GOING', 'WENT']);
    expect(statuses('over')).toEqual(['WENT']);
    expect(statuses('upcoming', 'CANCELLED')).toEqual([]);
  });

  it('counts going and interested before, went after, all three on the day', () => {
    const counts = { interested: 1, going: 2, went: 3 };
    expect(countsLine(counts, 'upcoming')).toBe('2 going · 1 interested');
    expect(countsLine(counts, 'today')).toBe('2 going · 1 interested · 3 went');
    expect(countsLine(counts, 'over')).toBe('3 went');
  });

  it('a tap on the selected status clears it, except an assumed Went, which it confirms', () => {
    expect(nextAttendance({ myAttendance: null }, 'GOING')).toBe('GOING');
    expect(nextAttendance({ myAttendance: 'GOING' }, 'GOING')).toBeNull();
    expect(nextAttendance({ myAttendance: 'WENT', wentUnconfirmed: false }, 'WENT')).toBeNull();
    expect(nextAttendance({ myAttendance: 'WENT', wentUnconfirmed: true }, 'WENT')).toBe('WENT');
  });

  it('confirming an assumed Went changes no count', () => {
    const assumed = {
      gig: { id: 'g1', myAttendance: 'WENT', wentUnconfirmed: true },
      attendanceCounts: { interested: 0, going: 0, went: 2 },
    } as unknown as GigDetail;
    const next = applyAttendance(assumed, 'WENT');
    expect(next.gig.wentUnconfirmed).toBe(false);
    expect(next.attendanceCounts.went).toBe(2);
  });
});
