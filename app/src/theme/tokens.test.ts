import { contrast } from './contrast';
import { THEME_ORDER, themes, type ColorScheme } from './tokens';

const schemes: ColorScheme[] = ['dark', 'light'];
const cases = THEME_ORDER.flatMap((id) => schemes.map((scheme) => [id, scheme] as const));

describe('theme palettes', () => {
  it.each(cases)('%s / %s: body and muted text are readable (WCAG AA 4.5:1)', (id, scheme) => {
    const c = themes[id].palettes[scheme];
    for (const ground of [c.background, c.surface, c.surfaceRaised]) {
      expect(contrast(c.text, ground)).toBeGreaterThanOrEqual(4.5);
      expect(contrast(c.textMuted, ground)).toBeGreaterThanOrEqual(4.5);
    }
  });

  it.each(cases)('%s / %s: labels on the accent and on tinted tiles are readable', (id, scheme) => {
    const c = themes[id].palettes[scheme];
    expect(contrast(c.onAccent, c.accent)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(c.text, c.accentSoft)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(c.text, c.accent2Soft)).toBeGreaterThanOrEqual(4.5);
  });

  it.each(cases)(
    '%s / %s: the accents stand out from the background (3:1, non-text)',
    (id, scheme) => {
      const c = themes[id].palettes[scheme];
      expect(contrast(c.accent, c.background)).toBeGreaterThanOrEqual(3);
      expect(contrast(c.accent2, c.background)).toBeGreaterThanOrEqual(3);
    },
  );

  it('every theme defines the same roles in both variants', () => {
    const roles = Object.keys(themes.original.palettes.dark).sort();
    for (const id of THEME_ORDER) {
      for (const scheme of schemes) {
        expect(Object.keys(themes[id].palettes[scheme]).sort()).toEqual(roles);
      }
    }
  });
});

describe('resolveScheme', () => {
  // Imported here so the palette tests above stay free of React.
  const { resolveScheme } = jest.requireActual<typeof import('./ThemeProvider')>('./ThemeProvider');

  it('uses a fixed mode whatever the phone says', () => {
    expect(resolveScheme('light', 'dark', 'dark')).toBe('light');
    expect(resolveScheme('dark', 'light', 'light')).toBe('dark');
  });

  it('follows the phone in auto, and the theme when the phone has no preference', () => {
    expect(resolveScheme('auto', 'light', 'dark')).toBe('light');
    expect(resolveScheme('auto', null, 'light')).toBe('light');
    expect(resolveScheme('auto', 'unspecified', 'dark')).toBe('dark');
  });
});
