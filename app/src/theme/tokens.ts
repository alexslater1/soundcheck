/**
 * Design tokens. A plain `StyleSheet` token system is used instead of NativeWind.
 *
 * Colour comes as a **theme** with a dark and a light palette of the same roles, so every screen reads
 * `colors.<role>` and never a hex value. Five themes were tried in the app; Electric was chosen and
 * the others were removed. The `Theme` shape stays, so a palette can be added again without
 * touching the screens.
 */
export type Colors = {
  background: string;
  surface: string;
  surfaceRaised: string;
  border: string;
  text: string;
  textMuted: string;
  /** Buttons, the selected tab, selected chips. */
  accent: string;
  /** Text and icons drawn on `accent` (white on a dark accent, black on a light one). */
  onAccent: string;
  /** A tinted surface: selected states, date tiles, highlighted calendar days. */
  accentSoft: string;
  /** The second hue: likes, live/"tonight" badges, the attendance mark. */
  accent2: string;
  accent2Soft: string;
  success: string;
  warning: string;
  danger: string;
};

export type ColorScheme = 'dark' | 'light';

export type ThemeId = 'electric';

export type Theme = {
  id: ThemeId;
  name: string;
  description: string;
  /** Which variant the "Auto" mode picks when the system has no preference. */
  prefers: ColorScheme;
  palettes: Record<ColorScheme, Colors>;
};

export const themes: Record<ThemeId, Theme> = {
  electric: {
    id: 'electric',
    name: 'Electric',
    description: 'True black, violet and cyan: club lights.',
    prefers: 'dark',
    palettes: {
      dark: {
        background: '#09090B',
        surface: '#141418',
        surfaceRaised: '#1F1F27',
        border: '#2E2E3A',
        text: '#FAFAFA',
        textMuted: '#A1A1AA',
        accent: '#7C3AED',
        onAccent: '#FFFFFF',
        accentSoft: '#2A1F4D',
        accent2: '#22D3EE',
        accent2Soft: '#0F3540',
        success: '#34D399',
        warning: '#FBBF24',
        danger: '#FB7185',
      },
      light: {
        background: '#F7F6FB',
        surface: '#FFFFFF',
        surfaceRaised: '#ECEAF5',
        border: '#D9D6E8',
        text: '#14121C',
        textMuted: '#5E5B70',
        accent: '#6D28D9',
        onAccent: '#FFFFFF',
        accentSoft: '#E9E0FD',
        accent2: '#0891B2',
        accent2Soft: '#D3F3FA',
        success: '#059669',
        warning: '#B45309',
        danger: '#E11D48',
      },
    },
  },
};

export const THEME_ORDER: ThemeId[] = ['electric'];
export const DEFAULT_THEME: ThemeId = 'electric';

export const spacing = { xs: 4, sm: 8, md: 16, lg: 24, xl: 32, xxl: 48 } as const;

export const radius = { sm: 6, md: 12, lg: 20, pill: 999 } as const;

export const typeScale = {
  display: { fontSize: 32, lineHeight: 38, fontWeight: '700' },
  title: { fontSize: 22, lineHeight: 28, fontWeight: '700' },
  body: { fontSize: 16, lineHeight: 22, fontWeight: '400' },
  caption: { fontSize: 13, lineHeight: 18, fontWeight: '400' },
  label: { fontSize: 12, lineHeight: 16, fontWeight: '600', letterSpacing: 0.6 },
} as const;

/** Minimum touch target (accessibility). */
export const touchTarget = 44;
