import { themes } from '@/theme/tokens';

import { chartPalette, MAX_SERIES, SERIES_COLORS } from './palette';

describe('chart palette', () => {
  it.each(['dark', 'light'] as const)(
    '%s: four distinct series colours, none of them a status colour',
    (scheme) => {
      const colors = themes.electric.palettes[scheme];
      const palette = chartPalette(colors, scheme);
      expect(palette.series).toHaveLength(MAX_SERIES);
      expect(new Set(palette.series).size).toBe(MAX_SERIES);
      for (const status of [colors.success, colors.warning, colors.danger]) {
        expect(palette.series).not.toContain(status);
      }
      expect(palette.text).toBe(colors.text);
    },
  );

  it('gives each scheme its own validated steps, not the same hexes', () => {
    expect(SERIES_COLORS.dark).not.toEqual(SERIES_COLORS.light);
  });
});
