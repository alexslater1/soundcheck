import type { ColorScheme, Colors } from '@/theme/tokens';

import type { ChartPalette } from './chartTypes';

/**
 * Series colours per scheme, each its own validated set rather than a flip of the other (checked with
 * a palette validator, 2025-08-19): dark against the chart surface #141418, light against #FFFFFF.
 * Both pass the lightness band, chroma floor, adjacent CVD separation (worst ΔE 8.2 dark, 9.3 light),
 * the normal-vision floor and 3:1 contrast. Violet and cyan lead, as in the Electric theme; status
 * colours (success, warning, danger) are never used for a series.
 */
export const SERIES_COLORS: Record<ColorScheme, readonly string[]> = {
  dark: ['#9061F9', '#1898BA', '#E0559B', '#5E9E32'],
  light: ['#7C4DEB', '#0E8FB0', '#D1427F', '#4F8A23'],
};

/** At most this many series per chart; a fifth folds into "Other" or another chart. */
export const MAX_SERIES = 4;

export function chartPalette(colors: Colors, scheme: ColorScheme): ChartPalette {
  const series = SERIES_COLORS[scheme];
  return {
    series,
    up: series[1] ?? colors.accent2,
    down: series[2] ?? colors.danger,
    grid: colors.border,
    axis: colors.textMuted,
    text: colors.text,
    surface: colors.surface,
  };
}
