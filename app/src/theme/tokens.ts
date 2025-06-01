/**
 * Design tokens. A plain `StyleSheet` token system is used instead of NativeWind.
 *
 * Colour comes in **themes** that the user switches in Settings → Appearance (the owner wanted to
 * see options in the app before choosing). Each theme has a dark and a light palette with the same
 * roles, so every screen reads `colors.<role>` and never a hex value. Compared with the first palette
 * (near-black with one pink accent), every theme has a second hue and tinted "soft" surfaces, which is
 * what lets cards, chips and date tiles carry colour instead of everything being black and grey.
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

export type ThemeId = 'stage' | 'electric' | 'acid' | 'poster' | 'original';

export type Theme = {
  id: ThemeId;
  name: string;
  description: string;
  /** Which variant the "Auto" mode picks when the system has no preference. */
  prefers: ColorScheme;
  palettes: Record<ColorScheme, Colors>;
};

export const themes: Record<ThemeId, Theme> = {
  stage: {
    id: 'stage',
    name: 'Stage light',
    description: 'Warm blacks, amber and ember, like a lit stage.',
    prefers: 'dark',
    palettes: {
      dark: {
        background: '#0F0D0B',
        surface: '#1A1714',
        surfaceRaised: '#26211C',
        border: '#3A322A',
        text: '#F7F2EA',
        textMuted: '#B3A898',
        accent: '#FFB224',
        onAccent: '#1A1206',
        accentSoft: '#3A2A10',
        accent2: '#FF6B3D',
        accent2Soft: '#3D1E14',
        success: '#4ADE80',
        warning: '#FACC15',
        danger: '#F87171',
      },
      light: {
        background: '#FBF7F0',
        surface: '#FFFFFF',
        surfaceRaised: '#F1E9DC',
        border: '#E2D6C3',
        text: '#1C1712',
        textMuted: '#6E6253',
        accent: '#A35F00',
        onAccent: '#FFFFFF',
        accentSoft: '#FCE9C8',
        accent2: '#D9481C',
        accent2Soft: '#FADCD0',
        success: '#15803D',
        warning: '#A16207',
        danger: '#C2410C',
      },
    },
  },
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
  acid: {
    id: 'acid',
    name: 'Acid',
    description: 'Black with acid lime and hot pink, like a rave flyer.',
    prefers: 'dark',
    palettes: {
      dark: {
        background: '#0A0A0A',
        surface: '#151515',
        surfaceRaised: '#202020',
        border: '#303030',
        text: '#F5F5F5',
        textMuted: '#A3A3A3',
        accent: '#C6F432',
        onAccent: '#0A0A0A',
        accentSoft: '#26300C',
        accent2: '#FF4F8B',
        accent2Soft: '#3D1426',
        success: '#C6F432',
        warning: '#FDE047',
        danger: '#FF5C5C',
      },
      light: {
        background: '#F7F8F2',
        surface: '#FFFFFF',
        surfaceRaised: '#ECEEE3',
        border: '#D6D9C8',
        text: '#111111',
        textMuted: '#5B5E52',
        accent: '#4D7C0F',
        onAccent: '#FFFFFF',
        accentSoft: '#E4F5C2',
        accent2: '#DB2777',
        accent2Soft: '#FBDCEA',
        success: '#3F6212',
        warning: '#A16207',
        danger: '#DC2626',
      },
    },
  },
  poster: {
    id: 'poster',
    name: 'Gig poster',
    description: 'Cream paper, ink and poster red. Light first.',
    prefers: 'light',
    palettes: {
      light: {
        background: '#F6F1E7',
        surface: '#FFFDF8',
        surfaceRaised: '#ECE5D8',
        border: '#D9CFBD',
        text: '#141210',
        textMuted: '#6B645A',
        accent: '#C9380F',
        onAccent: '#FFFFFF',
        accentSoft: '#FAD9CC',
        accent2: '#1F4FD8',
        accent2Soft: '#D7E1FA',
        success: '#1E7A4A',
        warning: '#B7791F',
        danger: '#C53030',
      },
      dark: {
        background: '#171513',
        surface: '#22201C',
        surfaceRaised: '#2E2B26',
        border: '#433E36',
        text: '#F3EDE1',
        textMuted: '#ADA495',
        accent: '#FF6A3D',
        onAccent: '#1A0D06',
        accentSoft: '#45251A',
        accent2: '#7FA2FF',
        accent2Soft: '#1D2A4D',
        success: '#5FD39A',
        warning: '#F2C14E',
        danger: '#FF7A7A',
      },
    },
  },
  original: {
    id: 'original',
    name: 'Original',
    description:
      'The first palette, kept for comparison (accent darkened for readable button text).',
    prefers: 'dark',
    palettes: {
      dark: {
        background: '#0B0B10',
        surface: '#15151D',
        surfaceRaised: '#1E1E29',
        border: '#2A2A38',
        text: '#F4F4F8',
        textMuted: '#A0A0B2',
        accent: '#D92D52',
        onAccent: '#FFFFFF',
        accentSoft: '#3A1520',
        accent2: '#FF8FA3',
        accent2Soft: '#3A1E26',
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
        accent: '#D42A48',
        onAccent: '#FFFFFF',
        accentSoft: '#FBDDE3',
        accent2: '#E0476A',
        accent2Soft: '#FDE6EB',
        success: '#1FA86E',
        warning: '#C98F1A',
        danger: '#D43F3F',
      },
    },
  },
};

/** Listed in this order in Settings → Appearance; the first is the default. */
export const THEME_ORDER: ThemeId[] = ['stage', 'electric', 'acid', 'poster', 'original'];
export const DEFAULT_THEME: ThemeId = 'stage';

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
