import { createContext, useContext, useMemo, type PropsWithChildren } from 'react';
import { useColorScheme } from 'react-native';

import { useThemePrefs, type ThemeMode } from './themePrefs';
import { DEFAULT_THEME, themes, type ColorScheme, type Colors, type ThemeId } from './tokens';

type ThemeValue = { themeId: ThemeId; scheme: ColorScheme; colors: Colors };

const ThemeContext = createContext<ThemeValue>({
  themeId: DEFAULT_THEME,
  scheme: 'dark',
  colors: themes[DEFAULT_THEME].palettes.dark,
});

/**
 * Which variant to show: a fixed mode wins; "auto" follows the phone, and when the phone has no
 * preference (the web, older Android) the theme's own preferred variant is used.
 */
export function resolveScheme(
  mode: ThemeMode,
  system: string | null | undefined,
  prefers: ColorScheme,
): ColorScheme {
  if (mode !== 'auto') {
    return mode;
  }
  return system === 'light' || system === 'dark' ? system : prefers;
}

/** Provides the user's theme (Settings → Appearance) in the right variant. */
export function ThemeProvider({ children }: PropsWithChildren) {
  const system = useColorScheme();
  const themeId = useThemePrefs((state) => state.themeId);
  const mode = useThemePrefs((state) => state.mode);
  const value = useMemo<ThemeValue>(() => {
    const theme = themes[themeId];
    const scheme = resolveScheme(mode, system, theme.prefers);
    return { themeId, scheme, colors: theme.palettes[scheme] };
  }, [themeId, mode, system]);
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme(): ThemeValue {
  return useContext(ThemeContext);
}
