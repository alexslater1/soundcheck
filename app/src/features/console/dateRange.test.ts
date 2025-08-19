import { MAX_RANGE_DAYS, parseRange, presetRange, rangeParams, rangeQuery } from './dateRange';

const now = new Date('2026-10-04T12:00:00.000Z');
const DAY = 24 * 60 * 60 * 1000;

describe('parseRange', () => {
  it('defaults to the last 7 days', () => {
    const r = parseRange({}, now);
    expect(r.preset).toBe('7d');
    expect(r.to).toEqual(now);
    expect(now.getTime() - r.from.getTime()).toBe(7 * DAY);
  });

  it.each(['24h', '7d', '30d'] as const)('reads the %s preset relative to now', (preset) => {
    expect(parseRange({ range: preset }, now)).toEqual(presetRange(preset, now));
  });

  it('reads an explicit from/to as a custom range', () => {
    const r = parseRange({ from: '2026-09-01T00:00:00Z', to: '2026-09-15T00:00:00.000Z' }, now);
    expect(r).toEqual({
      from: new Date('2026-09-01T00:00:00Z'),
      to: new Date('2026-09-15T00:00:00Z'),
      preset: null,
    });
  });

  it('takes the first of a repeated parameter', () => {
    const r = parseRange({ from: ['2026-09-01T00:00:00Z', 'x'], to: '2026-09-02T00:00:00Z' }, now);
    expect(r.from).toEqual(new Date('2026-09-01T00:00:00Z'));
  });

  it.each([
    ['garbage', { from: 'yesterday', to: 'today' }],
    ['a bare year', { from: '2026', to: '2027' }],
    ['a missing end', { from: '2026-09-01T00:00:00Z' }],
    ['from after to', { from: '2026-09-15T00:00:00Z', to: '2026-09-01T00:00:00Z' }],
    ['from equal to to', { from: '2026-09-15T00:00:00Z', to: '2026-09-15T00:00:00Z' }],
    ['an unknown preset', { range: '1y' }],
  ])('falls back to the default for %s', (_name, params) => {
    expect(parseRange(params, now)).toEqual(presetRange('7d', now));
  });

  it(`refuses more than ${MAX_RANGE_DAYS} days`, () => {
    const to = '2026-10-01T00:00:00Z';
    const ok = new Date(Date.parse(to) - MAX_RANGE_DAYS * DAY).toISOString();
    const tooLong = new Date(Date.parse(to) - (MAX_RANGE_DAYS * DAY + 1000)).toISOString();
    expect(parseRange({ from: ok, to }, now).preset).toBeNull();
    expect(parseRange({ from: tooLong, to }, now).preset).toBe('7d');
  });
});

describe('rangeParams and rangeQuery', () => {
  it('writes a preset as a relative range and clears from/to', () => {
    expect(rangeParams(presetRange('30d', now))).toEqual({
      range: '30d',
      from: undefined,
      to: undefined,
    });
  });

  it('writes a custom range as instants that parse back to the same range', () => {
    const custom = {
      from: new Date('2026-09-01T00:00:00Z'),
      to: new Date('2026-09-02T06:00:00Z'),
      preset: null,
    };
    const params = rangeParams(custom);
    expect(params.range).toBeUndefined();
    expect(parseRange(params, now)).toEqual(custom);
  });

  it('gives the API ISO instants', () => {
    expect(rangeQuery(presetRange('24h', now))).toEqual({
      from: '2026-10-03T12:00:00.000Z',
      to: '2026-10-04T12:00:00.000Z',
    });
  });
});
