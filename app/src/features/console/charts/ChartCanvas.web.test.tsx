/**
 * @jest-environment jsdom
 */
import { act } from 'react';
import { createRoot } from 'react-dom/client';

import { themes } from '@/theme/tokens';

import { ChartCanvas } from './ChartCanvas.web';
import type { ChartSpec } from './chartTypes';
import { chartPalette } from './palette';

// The web drawing in jsdom: Recharts is plain React DOM, so a DOM is all it needs (no browser).
const palette = chartPalette(themes.electric.palettes.dark, 'dark');

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

function draw(spec: ChartSpec, width = 600): string {
  const host = document.createElement('div');
  const root = createRoot(host);
  act(() => {
    root.render(
      <ChartCanvas spec={spec} palette={palette} width={width} height={200} label="A summary" />,
    );
  });
  const html = host.innerHTML;
  act(() => root.unmount());
  return html;
}

describe('ChartCanvas (web)', () => {
  it.each<[string, ChartSpec]>([
    [
      'line',
      { kind: 'line', x: ['a', 'b', 'c'], series: [{ key: 's', label: 'S', values: [1, 3, 2] }] },
    ],
    [
      'bar',
      { kind: 'bar', categories: ['a', 'b'], series: [{ key: 's', label: 'S', values: [1, 2] }] },
    ],
    [
      'histogram',
      {
        kind: 'histogram',
        bins: [
          { from: 0, to: 1, count: 4 },
          { from: 1, to: 2, count: 1 },
        ],
      },
    ],
    [
      'waterfall',
      {
        kind: 'waterfall',
        start: { label: 'bias', value: -2 },
        steps: [{ label: 'recency', value: 1 }],
        totalLabel: 'logit',
      },
    ],
    ['sparkline', { kind: 'sparkline', values: [1, 2, 3] }],
  ])('draws a %s as an SVG labelled with its summary', (_kind, spec) => {
    const html = draw(spec);
    expect(html).toContain('role="img"');
    expect(html).toContain('aria-label="A summary"');
    expect(html).toContain('<svg');
  });

  it('uses the first series colour', () => {
    expect(
      draw({ kind: 'line', x: ['a', 'b'], series: [{ key: 's', label: 'S', values: [1, 2] }] }),
    ).toContain(palette.series[0]);
  });

  it('draws nothing before the frame has been measured', () => {
    expect(draw({ kind: 'sparkline', values: [1, 2] }, 0)).toBe('');
  });
});
