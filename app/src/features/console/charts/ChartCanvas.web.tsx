import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

import type { ChartPalette, ChartSpec } from './chartTypes';
import { fmt, waterfallBars } from './summary';

/**
 * The web side of the chart boundary: plain React DOM, which Recharts draws as SVG. Metro picks
 * this file on web only; `ChartCanvas.tsx` (native) renders nothing, so Recharts never reaches the
 * Android bundle. Marks follow one set of rules: 2 px lines, rounded bar ends, recessive grid, a
 * tooltip on hover, a legend for two or more series. Every chart has Recharts' accessibility layer
 * (focusable, arrow keys move the tooltip).
 */
export type ChartCanvasProps = {
  spec: ChartSpec;
  palette: ChartPalette;
  width: number;
  height: number;
  label: string;
};

const MARGIN = { top: 8, right: 16, bottom: 4, left: 0 };

export function ChartCanvas({ spec, palette, width, height, label }: ChartCanvasProps) {
  if (width <= 0) return null;
  const axis = {
    stroke: palette.axis,
    tick: { fill: palette.axis, fontSize: 12 },
    tickLine: false,
  };
  const tooltip = {
    contentStyle: {
      background: palette.surface,
      border: `1px solid ${palette.grid}`,
      color: palette.text,
      borderRadius: 8,
    },
    labelStyle: { color: palette.text },
    itemStyle: { color: palette.text },
  };
  const color = (i: number) => palette.series[i] ?? palette.axis;

  switch (spec.kind) {
    case 'line': {
      const data = spec.x.map((x, i) => {
        const row: Record<string, string | number | undefined> = { x };
        for (const s of spec.series) row[s.key] = s.values[i];
        return row;
      });
      return (
        <div role="img" aria-label={label}>
          <LineChart width={width} height={height} data={data} margin={MARGIN} accessibilityLayer>
            <CartesianGrid stroke={palette.grid} vertical={false} />
            <XAxis dataKey="x" {...axis} />
            <YAxis {...axis} width={48} />
            <Tooltip {...tooltip} formatter={(v) => fmt(Number(v), spec.unit)} />
            {spec.series.length > 1 ? <Legend wrapperStyle={{ color: palette.text }} /> : null}
            {spec.series.map((s, i) => (
              <Line
                key={s.key}
                dataKey={s.key}
                name={s.label}
                stroke={color(i)}
                strokeWidth={2}
                dot={false}
                isAnimationActive={false}
              />
            ))}
          </LineChart>
        </div>
      );
    }
    case 'bar': {
      const data = spec.categories.map((c, i) => {
        const row: Record<string, string | number | undefined> = { c };
        for (const s of spec.series) row[s.key] = s.values[i];
        return row;
      });
      return (
        <div role="img" aria-label={label}>
          <BarChart
            width={width}
            height={height}
            data={data}
            margin={MARGIN}
            barGap={2}
            accessibilityLayer
          >
            <CartesianGrid stroke={palette.grid} vertical={false} />
            <XAxis dataKey="c" {...axis} />
            <YAxis {...axis} width={48} />
            <Tooltip {...tooltip} formatter={(v) => fmt(Number(v), spec.unit)} />
            {spec.series.length > 1 ? <Legend wrapperStyle={{ color: palette.text }} /> : null}
            {spec.series.map((s, i) => (
              <Bar
                key={s.key}
                dataKey={s.key}
                name={s.label}
                fill={color(i)}
                radius={[4, 4, 0, 0]}
                isAnimationActive={false}
              />
            ))}
          </BarChart>
        </div>
      );
    }
    case 'histogram': {
      const data = spec.bins.map((b) => ({
        range: `${fmt(b.from, spec.unit)}–${fmt(b.to, spec.unit)}`,
        count: b.count,
      }));
      return (
        <div role="img" aria-label={label}>
          <BarChart
            width={width}
            height={height}
            data={data}
            margin={MARGIN}
            barCategoryGap={2}
            accessibilityLayer
          >
            <CartesianGrid stroke={palette.grid} vertical={false} />
            <XAxis dataKey="range" {...axis} />
            <YAxis {...axis} width={48} allowDecimals={false} />
            <Tooltip {...tooltip} />
            <Bar
              dataKey="count"
              name="Count"
              fill={color(0)}
              radius={[4, 4, 0, 0]}
              isAnimationActive={false}
            />
          </BarChart>
        </div>
      );
    }
    case 'waterfall': {
      // A floating bar is a stacked pair: an invisible base up to the bar's lower edge, then the bar.
      const data = waterfallBars(spec).map((b) => ({
        label: b.label,
        base: Math.min(b.start, b.end),
        size: Math.abs(b.end - b.start),
        value: b.value,
        kind: b.kind,
      }));
      return (
        <div role="img" aria-label={label}>
          <BarChart width={width} height={height} data={data} margin={MARGIN} accessibilityLayer>
            <CartesianGrid stroke={palette.grid} vertical={false} />
            <XAxis dataKey="label" {...axis} interval={0} />
            <YAxis {...axis} width={48} />
            <Tooltip
              {...tooltip}
              formatter={(_v, name, item) =>
                name === 'size'
                  ? [fmt(Number((item.payload as { value: number }).value)), 'Value']
                  : null
              }
            />
            {/* The invisible base has no row in the tooltip. */}
            <Bar
              dataKey="base"
              stackId="w"
              fill="transparent"
              tooltipType="none"
              isAnimationActive={false}
            />
            <Bar dataKey="size" stackId="w" radius={[4, 4, 4, 4]} isAnimationActive={false}>
              {data.map((d) => (
                <Cell
                  key={d.label}
                  fill={d.kind === 'up' ? palette.up : d.kind === 'down' ? palette.down : color(0)}
                />
              ))}
            </Bar>
          </BarChart>
        </div>
      );
    }
    case 'sparkline': {
      const data = spec.values.map((v, i) => ({ i, v }));
      return (
        <div role="img" aria-label={label}>
          <LineChart
            width={width}
            height={height}
            data={data}
            margin={{ top: 2, right: 2, bottom: 2, left: 2 }}
            accessibilityLayer
          >
            <Line
              dataKey="v"
              stroke={color(0)}
              strokeWidth={2}
              dot={false}
              isAnimationActive={false}
            />
          </LineChart>
        </div>
      );
    }
  }
}
