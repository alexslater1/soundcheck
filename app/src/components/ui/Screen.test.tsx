import { HeaderHeightContext } from '@react-navigation/elements';
import { render, screen } from '@testing-library/react-native';
import type { ReactNode } from 'react';
import { StyleSheet, Text } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { ThemeProvider } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

import { Screen } from './Screen';

const metrics = {
  frame: { x: 0, y: 0, width: 412, height: 915 },
  insets: { top: 24, left: 0, right: 0, bottom: 16 },
};

function Providers({ children }: { children: ReactNode }) {
  return (
    <SafeAreaProvider initialMetrics={metrics}>
      <ThemeProvider>{children}</ThemeProvider>
    </SafeAreaProvider>
  );
}

const column = () => StyleSheet.flatten(screen.getByTestId('column').props.style);

describe('Screen under a page header', () => {
  it('starts its column at the header’s bottom edge: no 16 pt gap that stays while scrolling', async () => {
    await render(
      <Providers>
        <HeaderHeightContext.Provider value={80}>
          <Screen testID="column">
            <Text>Privacy</Text>
          </Screen>
        </HeaderHeightContext.Provider>
      </Providers>,
    );

    expect(column().paddingTop).toBe(80);
  });

  it('keeps its usual padding where there is no header (the tabs)', async () => {
    await render(
      <Providers>
        <Screen testID="column">
          <Text>Home</Text>
        </Screen>
      </Providers>,
    );

    expect(column().paddingTop).toBeUndefined();
    expect(column().padding).toBe(spacing.md);
  });

  it('keeps room for the header even when a screen asks for no top padding', async () => {
    await render(
      <Providers>
        <HeaderHeightContext.Provider value={80}>
          <Screen testID="column" style={{ paddingTop: 0 }}>
            <Text>Post</Text>
          </Screen>
        </HeaderHeightContext.Provider>
      </Providers>,
    );

    expect(column().paddingTop).toBe(80);
  });
});
