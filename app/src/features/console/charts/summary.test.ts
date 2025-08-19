import { fmt, histogram, summarise, tableOf, waterfallBars } from './summary';

describe('fmt', () => {
  it('rounds to two decimals and separates thousands', () => {
    expect(fmt(1234.5678)).toBe('1,234.57');
    expect(fmt(0.4, ' %')).toBe('0.4 %');
  });
});

describe('summarise', () => {
  it('names each line series with its first and last value', () => {
    expect(
      summarise(
        {
          kind: 'line',
          x: ['1 Oct', '2 Oct', '3 Oct'],
          series: [
            { key: 'dau', label: 'DAU', values: [10, 12, 15] },
            { key: 'new', label: 'New users', values: [1, 0, 2] },
          ],
        },
        'Daily activity',
      ),
    ).toBe('Daily activity: line chart, 1 Oct to 3 Oct. DAU from 10 to 15; New users from 1 to 2.');
  });

  it('names the largest bar', () => {
    expect(
      summarise(
        {
          kind: 'bar',
          categories: ['photo', 'video'],
          series: [{ key: 'r', label: 'Rate', values: [0.2, 0.35] }],
          unit: '',
        },
        'Engagement',
      ),
    ).toBe('Engagement: bar chart of 2 categories. Rate highest at video (0.35).');
  });

  it('says where most of a histogram is', () => {
    expect(
      summarise({ kind: 'histogram', bins: histogram([0.1, 0.2, 0.9, 0.95, 1], 2) }, 'Watch'),
    ).toBe('Watch: histogram of 5 values; most between 0.55 and 1 (3).');
  });

  it('gives a waterfall its start and total', () => {
    expect(
      summarise(
        {
          kind: 'waterfall',
          start: { label: 'bias', value: -2 },
          steps: [
            { label: 'recency', value: 1.5 },
            { label: 'seen', value: -0.5 },
          ],
          totalLabel: 'logit',
        },
        'Score',
      ),
    ).toBe('Score: waterfall from bias -2 through 2 steps to logit -1.');
  });

  it('copes with no data', () => {
    expect(summarise({ kind: 'sparkline', values: [] }, 'DAU')).toBe('DAU: no data.');
    expect(summarise({ kind: 'histogram', bins: [] }, 'Watch')).toBe(
      'Watch: histogram with no values.',
    );
  });
});

describe('histogram', () => {
  it('makes equal bins and puts the maximum in the last one', () => {
    const bins = histogram([0, 0.25, 0.5, 0.75, 1], 4);
    expect(bins.map((b) => b.count)).toEqual([1, 1, 1, 2]);
    expect(bins[0]).toEqual({ from: 0, to: 0.25, count: 1 });
  });

  it('puts identical values in one bin', () => {
    expect(histogram([3, 3, 3], 2).map((b) => b.count)).toEqual([3, 0]);
  });

  it('is empty for no values', () => {
    expect(histogram([], 5)).toEqual([]);
  });
});

describe('waterfallBars', () => {
  it('floats each step from where the last one ended, and ends at the total', () => {
    expect(
      waterfallBars({
        start: { label: 'bias', value: -2 },
        steps: [
          { label: 'recency', value: 1.5 },
          { label: 'seen', value: -0.5 },
        ],
        totalLabel: 'logit',
      }),
    ).toEqual([
      { label: 'bias', value: -2, start: 0, end: -2, kind: 'start' },
      { label: 'recency', value: 1.5, start: -2, end: -0.5, kind: 'up' },
      { label: 'seen', value: -0.5, start: -0.5, end: -1, kind: 'down' },
      { label: 'logit', value: -1, start: 0, end: -1, kind: 'total' },
    ]);
  });
});

describe('tableOf', () => {
  it('turns a line chart into rows per x', () => {
    expect(
      tableOf({
        kind: 'line',
        x: ['a', 'b'],
        series: [{ key: 's', label: 'S', values: [1, 2] }],
        unit: ' ms',
      }),
    ).toEqual({
      headers: ['', 'S'],
      rows: [
        ['a', '1 ms'],
        ['b', '2 ms'],
      ],
    });
  });

  it('gives a waterfall its running total', () => {
    expect(
      tableOf({
        kind: 'waterfall',
        start: { label: 'bias', value: 1 },
        steps: [{ label: 'x', value: 2 }],
        totalLabel: 'logit',
      }).rows,
    ).toEqual([
      ['bias', '1', '1'],
      ['x', '2', '3'],
      ['logit', '3', '3'],
    ]);
  });
});
