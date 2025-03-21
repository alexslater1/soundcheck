import { refreshedAnchor, WINDOW_MAX_AGE_MS } from './useWindowAnchor';

describe('refreshedAnchor', () => {
  const noon = Date.parse('2026-10-02T12:00:00Z');

  it('keeps a fresh anchor so the query key stays stable', () => {
    expect(refreshedAnchor(noon, noon + 1_000)).toBe(noon);
    expect(refreshedAnchor(noon, noon + WINDOW_MAX_AGE_MS)).toBe(noon);
  });

  it('moves a stale anchor to now (a tab left open overnight)', () => {
    const nextMorning = noon + 20 * 60 * 60 * 1000;
    expect(refreshedAnchor(noon, nextMorning)).toBe(nextMorning);
    expect(refreshedAnchor(noon, noon + WINDOW_MAX_AGE_MS + 1)).toBe(noon + WINDOW_MAX_AGE_MS + 1);
  });
});
