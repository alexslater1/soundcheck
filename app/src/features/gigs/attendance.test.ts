import type { GigDetail } from '@/api/generated/model';

import { applyAttendance } from './attendance';

const detail = (my: GigDetail['gig']['myAttendance'], going: number, interested = 0): GigDetail =>
  ({
    gig: { id: 'g1', title: 'A gig', myAttendance: my },
    attendanceCounts: { interested, going, went: 0 },
    lineup: [],
    sources: [],
    postsCount: 0,
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
