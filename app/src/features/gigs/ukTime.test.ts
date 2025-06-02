import { instantAt, wallTime } from './ukTime';

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
