import { fireEvent, render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';
import { useThemePrefs } from '@/theme/themePrefs';

import { AppearanceScreen } from './AppearanceScreen';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

describe('AppearanceScreen', () => {
  beforeEach(() => useThemePrefs.setState({ themeId: 'stage', mode: 'auto' }));

  it('shows every theme and applies the one tapped', async () => {
    await render(
      <ThemeProvider>
        <AppearanceScreen />
      </ThemeProvider>,
    );
    for (const id of ['stage', 'electric', 'acid', 'poster', 'original']) {
      expect(screen.getByTestId(`theme-${id}`)).toBeTruthy();
    }
    expect(screen.getByTestId('theme-stage')).toBeChecked();

    await fireEvent.press(screen.getByTestId('theme-electric'));

    expect(useThemePrefs.getState().themeId).toBe('electric');
    expect(screen.getByTestId('theme-electric')).toBeChecked();
  });

  it('switches between auto, dark and light', async () => {
    await render(
      <ThemeProvider>
        <AppearanceScreen />
      </ThemeProvider>,
    );
    await fireEvent.press(screen.getByText('Light'));
    expect(useThemePrefs.getState().mode).toBe('light');
  });
});
