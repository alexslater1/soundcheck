import type { ChartSpec, HistogramBin } from './chartTypes';

/** A number for people: at most two decimals, thousands separated. */
export function fmt(value: number, unit = ''): string {
  const rounded = Math.round(value * 100) / 100;
  return `${rounded.toLocaleString('en-GB')}${unit}`;
}

/**
 * One sentence saying what the chart shows (its `aria-label`, and the text a screen reader reads).
 * It names each series' range or the largest bar, so the chart is never colour or shape alone.
 */
export function summarise(spec: ChartSpec, title: string): string {
  switch (spec.kind) {
    case 'line': {
      const parts = spec.series.map((s) => {
        const first = s.values[0];
        const last = s.values[s.values.length - 1];
        return first === undefined || last === undefined
          ? `${s.label}: no data`
          : `${s.label} from ${fmt(first, spec.unit)} to ${fmt(last, spec.unit)}`;
      });
      const span = spec.x.length ? `, ${spec.x[0]} to ${spec.x[spec.x.length - 1]}` : '';
      return `${title}: line chart${span}. ${parts.join('; ')}.`;
    }
    case 'bar': {
      const parts = spec.series.map((s) => {
        let best = -1;
        s.values.forEach((v, i) => {
          if (best < 0 || v > (s.values[best] ?? -Infinity)) best = i;
        });
        return best < 0
          ? `${s.label}: no data`
          : `${s.label} highest at ${spec.categories[best]} (${fmt(s.values[best] ?? 0, spec.unit)})`;
      });
      return `${title}: bar chart of ${spec.categories.length} categories. ${parts.join('; ')}.`;
    }
    case 'histogram': {
      const total = spec.bins.reduce((sum, b) => sum + b.count, 0);
      const top = [...spec.bins].sort((a, b) => b.count - a.count)[0];
      return top
        ? `${title}: histogram of ${total} values; most between ${fmt(top.from, spec.unit)} and ${fmt(top.to, spec.unit)} (${top.count}).`
        : `${title}: histogram with no values.`;
    }
    case 'waterfall': {
      const total = spec.steps.reduce((sum, s) => sum + s.value, spec.start.value);
      return `${title}: waterfall from ${spec.start.label} ${fmt(spec.start.value)} through ${spec.steps.length} steps to ${spec.totalLabel} ${fmt(total)}.`;
    }
    case 'sparkline': {
      const first = spec.values[0];
      const last = spec.values[spec.values.length - 1];
      return first === undefined || last === undefined
        ? `${title}: no data.`
        : `${title}: trend from ${fmt(first)} to ${fmt(last)} over ${spec.values.length} points.`;
    }
  }
}

/** The "Show table" view of a chart: the same numbers as rows. */
export function tableOf(spec: ChartSpec): { headers: string[]; rows: string[][] } {
  switch (spec.kind) {
    case 'line':
      return {
        headers: ['', ...spec.series.map((s) => s.label)],
        rows: spec.x.map((x, i) => [
          x,
          ...spec.series.map((s) => fmt(s.values[i] ?? NaN, spec.unit)),
        ]),
      };
    case 'bar':
      return {
        headers: ['', ...spec.series.map((s) => s.label)],
        rows: spec.categories.map((c, i) => [
          c,
          ...spec.series.map((s) => fmt(s.values[i] ?? NaN, spec.unit)),
        ]),
      };
    case 'histogram':
      return {
        headers: ['From', 'To', 'Count'],
        rows: spec.bins.map((b) => [fmt(b.from, spec.unit), fmt(b.to, spec.unit), String(b.count)]),
      };
    case 'waterfall': {
      const bars = waterfallBars(spec);
      return {
        headers: ['Step', 'Value', 'Running total'],
        rows: bars.map((b) => [b.label, fmt(b.value), fmt(b.end)]),
      };
    }
    case 'sparkline':
      return { headers: ['#', 'Value'], rows: spec.values.map((v, i) => [String(i + 1), fmt(v)]) };
  }
}

/** Equal-width bins from `min` to `max` of the values; the last bin includes `max`. */
export function histogram(values: readonly number[], binCount: number): HistogramBin[] {
  if (!values.length || binCount < 1) return [];
  const min = Math.min(...values);
  const max = Math.max(...values);
  const width = max === min ? 1 : (max - min) / binCount;
  const bins: HistogramBin[] = Array.from({ length: binCount }, (_, i) => ({
    from: min + i * width,
    to: min + (i + 1) * width,
    count: 0,
  }));
  for (const v of values) {
    const i = Math.min(binCount - 1, Math.floor((v - min) / width));
    const bin = bins[i];
    if (bin) bin.count += 1;
  }
  return bins;
}

export type WaterfallBar = {
  label: string;
  value: number;
  /** Where the bar starts and ends on the value axis. */
  start: number;
  end: number;
  kind: 'start' | 'up' | 'down' | 'total';
};

/** The floating bars of a waterfall: each step starts where the previous one ended. */
export function waterfallBars(spec: {
  start: { label: string; value: number };
  steps: { label: string; value: number }[];
  totalLabel: string;
}): WaterfallBar[] {
  const bars: WaterfallBar[] = [
    {
      label: spec.start.label,
      value: spec.start.value,
      start: 0,
      end: spec.start.value,
      kind: 'start',
    },
  ];
  let running = spec.start.value;
  for (const step of spec.steps) {
    bars.push({
      label: step.label,
      value: step.value,
      start: running,
      end: running + step.value,
      kind: step.value >= 0 ? 'up' : 'down',
    });
    running += step.value;
  }
  bars.push({ label: spec.totalLabel, value: running, start: 0, end: running, kind: 'total' });
  return bars;
}
