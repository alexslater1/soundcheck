import { createContext, useContext, useMemo, type PropsWithChildren } from 'react';
import { useColorScheme } from 'react-native';

import { palette, type ColorScheme, type Colors } from './tokens';

type Theme = { scheme: ColorScheme; colors: Colors };

const ThemeContext = createContext<Theme>({ scheme: 'dark', colors: palette.dark });

/** Dark first: the system scheme is honoured, anything unknown falls back to dark. */
export function ThemeProvider({ children }: PropsWithChildren) {
  const system = useColorScheme();
  const value = useMemo<Theme>(() => {
    const scheme: ColorScheme = system === 'light' ? 'light' : 'dark';
    return { scheme, colors: palette[scheme] };
  }, [system]);
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme(): Theme {
  return useContext(ThemeContext);
}
