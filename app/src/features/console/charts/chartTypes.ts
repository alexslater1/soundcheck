/**
 * The chart kit's data shapes. Props are plain serialisable data, so the same spec could cross
 * a DOM-component boundary as well as a `*.web.tsx` one: no functions, no React nodes.
 */
export type SeriesSpec = { key: string; label: string; values: number[] };

export type LineChartSpec = {
  kind: 'line';
  /** One label per point (dates, usually), shared by every series. */
  x: string[];
  series: SeriesSpec[];
  unit?: string;
};

export type BarChartSpec = {
  kind: 'bar';
  categories: string[];
  series: SeriesSpec[];
  unit?: string;
};

export type HistogramBin = { from: number; to: number; count: number };

export type HistogramSpec = { kind: 'histogram'; bins: HistogramBin[]; unit?: string };

/** A score explained step by step: start (bias), then each contribution, ending at the total. */
export type WaterfallSpec = {
  kind: 'waterfall';
  start: { label: string; value: number };
  steps: { label: string; value: number }[];
  totalLabel: string;
};

export type SparklineSpec = { kind: 'sparkline'; values: number[] };

export type ChartSpec =
  | LineChartSpec
  | BarChartSpec
  | HistogramSpec
  | WaterfallSpec
  | SparklineSpec;

/** The categorical colours a chart may use, in fixed order (never cycled), plus its ink. */
export type ChartPalette = {
  series: readonly string[];
  /** Positive and negative steps of a waterfall (polarity, not identity). */
  up: string;
  down: string;
  grid: string;
  axis: string;
  text: string;
  surface: string;
};
