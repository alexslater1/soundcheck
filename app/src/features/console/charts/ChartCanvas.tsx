import type { ChartPalette, ChartSpec } from './chartTypes';

/**
 * The native side of the chart boundary: the console is web only, so on Android a chart draws
 * nothing and Recharts stays out of the bundle. `ChartCanvas.web.tsx` is the real one.
 */
export type ChartCanvasProps = {
  spec: ChartSpec;
  palette: ChartPalette;
  width: number;
  height: number;
  label: string;
};

export function ChartCanvas(_props: ChartCanvasProps) {
  return null;
}
