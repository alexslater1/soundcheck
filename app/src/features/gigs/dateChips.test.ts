import { chipRange, instantAt, wallTime } from './dateChips';

const iso = (range: { from: Date; to: Date }) => [range.from.toISOString(), range.to.toISOString()];

/** Date-chip ranges. Times are UK wall-clock times; October 2026 is BST until the 25th. */
describe('chipRange', () => {
  // Friday 2 Oct 2026, 15:00 in London (14:00 UTC, BST).
  const fridayAfternoon = new Date('2026-10-02T14:00:00Z');

  it('tonight runs from now until 04:00 tomorrow, UK time', () => {
    expect(iso(chipRange('tonight', fridayAfternoon))).toEqual([
      '2026-10-02T14:00:00.000Z',
      '2026-10-03T03:00:00.000Z', // 04:00 BST
    ]);
  });

  it('after midnight, tonight is the night that is still going (ends 04:00 today)', () => {
    const halfPastOne = new Date('2026-10-03T00:30:00Z'); // 01:30 BST on Saturday
    expect(iso(chipRange('tonight', halfPastOne))).toEqual([
      '2026-10-03T00:30:00.000Z',
      '2026-10-03T03:00:00.000Z',
    ]);
  });

  it.each([
    // [name, now (UTC), expected from, expected to]
    [
      'on Wednesday: Friday 17:00 → Monday 00:00',
      '2026-09-30T09:00:00Z',
      '2026-10-02T16:00:00.000Z',
      '2026-10-04T23:00:00.000Z',
    ],
    [
      'on Friday before 17:00: starts at 17:00 today',
      '2026-10-02T14:00:00Z',
      '2026-10-02T16:00:00.000Z',
      '2026-10-04T23:00:00.000Z',
    ],
    [
      'on Friday evening: starts now',
      '2026-10-02T19:30:00Z',
      '2026-10-02T19:30:00.000Z',
      '2026-10-04T23:00:00.000Z',
    ],
    [
      'on Saturday: starts now, same Monday',
      '2026-10-03T12:00:00Z',
      '2026-10-03T12:00:00.000Z',
      '2026-10-04T23:00:00.000Z',
    ],
    [
      'on Sunday night: starts now, ends at midnight',
      '2026-10-04T21:00:00Z',
      '2026-10-04T21:00:00.000Z',
      '2026-10-04T23:00:00.000Z',
    ],
    [
      'on Monday: the coming weekend',
      '2026-10-05T08:00:00Z',
      '2026-10-09T16:00:00.000Z',
      '2026-10-11T23:00:00.000Z',
    ],
    [
      'the weekend the clocks go back: Friday is BST, Monday is GMT',
      '2026-10-21T10:00:00Z',
      '2026-10-23T16:00:00.000Z', // Fri 17:00 BST
      '2026-10-26T00:00:00.000Z', // Mon 00:00 GMT
    ],
  ])('this weekend %s', (_name, now, from, to) => {
    expect(iso(chipRange('weekend', new Date(now)))).toEqual([from, to]);
  });

  it('next 7 and next 30 days are rolling windows from now', () => {
    expect(iso(chipRange('week', fridayAfternoon))).toEqual([
      '2026-10-02T14:00:00.000Z',
      '2026-10-09T14:00:00.000Z',
    ]);
    expect(iso(chipRange('month', fridayAfternoon))).toEqual([
      '2026-10-02T14:00:00.000Z',
      '2026-11-01T14:00:00.000Z',
    ]);
  });

  it('every range is non-empty', () => {
    for (let hour = 0; hour < 24 * 14; hour += 5) {
      const now = new Date(Date.UTC(2026, 9, 19, hour)); // two weeks spanning the clock change
      for (const chip of ['tonight', 'weekend', 'week', 'month'] as const) {
        const { from, to } = chipRange(chip, now);
        expect(to.getTime()).toBeGreaterThan(from.getTime());
      }
    }
  });
});

describe('UK wall-clock helpers', () => {
  it('reads and builds wall times on both sides of the clock change', () => {
    expect(wallTime(new Date('2026-07-01T18:30:00Z'), 'Europe/London')).toMatchObject({
      hour: 19,
      minute: 30,
    });
    expect(wallTime(new Date('2026-12-01T18:30:00Z'), 'Europe/London')).toMatchObject({ hour: 18 });
    expect(instantAt('Europe/London', 2026, 7, 1, 19, 30).toISOString()).toBe(
      '2026-07-01T18:30:00.000Z',
    );
    expect(instantAt('Europe/London', 2026, 12, 1, 19, 30).toISOString()).toBe(
      '2026-12-01T19:30:00.000Z',
    );
    // Day overflow: "31 October + 1" is 1 November.
    expect(instantAt('Europe/London', 2026, 10, 32, 4).toISOString()).toBe(
      '2026-11-01T04:00:00.000Z',
    );
  });
});
