/**
 * Design tokens: dark theme first (concert aesthetic), light theme supported.
 * A plain `StyleSheet` token system is used instead of NativeWind.
 */
export const palette = {
  dark: {
    background: '#0B0B10',
    surface: '#15151D',
    surfaceRaised: '#1E1E29',
    border: '#2A2A38',
    text: '#F4F4F8',
    textMuted: '#A0A0B2',
    accent: '#FF4D6D',
    accentMuted: '#FF8FA3',
    success: '#3DDC97',
    warning: '#FFC857',
    danger: '#FF5C5C',
  },
  light: {
    background: '#FFFFFF',
    surface: '#F5F5F9',
    surfaceRaised: '#EBEBF2',
    border: '#D8D8E2',
    text: '#111118',
    textMuted: '#5C5C70',
    accent: '#E0304F',
    accentMuted: '#F26C85',
    success: '#1FA86E',
    warning: '#C98F1A',
    danger: '#D43F3F',
  },
} as const;

export type ColorScheme = keyof typeof palette;
export type Colors = (typeof palette)[ColorScheme];

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
