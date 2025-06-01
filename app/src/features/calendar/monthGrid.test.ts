import {
  addMonths,
  dayWindow,
  isoDate,
  monthGrid,
  monthOf,
  monthTitle,
  monthWindow,
  ukDate,
} from './monthGrid';

describe('month grid', () => {
  it('lays October 2026 out Monday first, padded with September and November', () => {
    // 1 October 2026 is a Thursday.
    const weeks = monthGrid({ year: 2026, month: 10 });
    expect(weeks).toHaveLength(5);
    expect(weeks.every((week) => week.length === 7)).toBe(true);
    expect(weeks[0]!.map((c) => c.date)).toEqual([
      '2026-09-28',
      '2026-09-29',
      '2026-09-30',
      '2026-10-01',
      '2026-10-02',
      '2026-10-03',
      '2026-10-04',
    ]);
    expect(weeks[0]![2]!.inMonth).toBe(false);
    expect(weeks[0]![3]).toEqual({ date: '2026-10-01', day: 1, inMonth: true });
    expect(weeks[4]!.at(-1)).toEqual({ date: '2026-11-01', day: 1, inMonth: false });
  });

  it('needs six rows for a month that starts on a Sunday with 31 days, four for a perfect February', () => {
    expect(monthGrid({ year: 2026, month: 3 })).toHaveLength(6); // 1 March 2026: Sunday
    expect(monthGrid({ year: 2027, month: 2 })).toHaveLength(4); // 1 Feb 2027: Monday, 28 days
  });

  it('steps across years', () => {
    expect(addMonths({ year: 2026, month: 12 }, 1)).toEqual({ year: 2027, month: 1 });
    expect(addMonths({ year: 2026, month: 1 }, -1)).toEqual({ year: 2025, month: 12 });
    expect(monthTitle({ year: 2026, month: 10 })).toBe('October 2026');
    expect(isoDate(2026, 3, 0)).toBe('2026-02-28');
  });

  it('puts instants on the UK calendar, summer time included', () => {
    expect(ukDate('2026-10-02T23:30:00Z')).toBe('2026-10-03'); // 00:30 BST
    expect(ukDate('2026-12-02T23:30:00Z')).toBe('2026-12-02'); // 23:30 GMT
    expect(monthOf(new Date('2026-09-30T23:30:00Z'))).toEqual({ year: 2026, month: 10 });
  });

  it('bounds a month and a day on the UK clock', () => {
    expect(monthWindow({ year: 2026, month: 10 })).toEqual({
      from: '2026-09-30T23:00:00.000Z', // midnight BST
      to: '2026-11-01T00:00:00.000Z', // midnight GMT, after the clocks went back
    });
    expect(dayWindow('2026-10-25')).toEqual({
      from: '2026-10-24T23:00:00.000Z',
      to: '2026-10-26T00:00:00.000Z', // the 25-hour day
    });
  });
});
