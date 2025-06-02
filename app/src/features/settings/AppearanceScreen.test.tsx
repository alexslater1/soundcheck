import { fireEvent, render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';
import { useThemePrefs } from '@/theme/themePrefs';

import { AppearanceScreen } from './AppearanceScreen';

describe('AppearanceScreen', () => {
  beforeEach(() => useThemePrefs.setState({ themeId: 'electric', mode: 'auto' }));

  it('switches between auto, dark and light', async () => {
    await render(
      <ThemeProvider>
        <AppearanceScreen />
      </ThemeProvider>,
    );
    expect(screen.getByText("Follows the phone's dark or light setting.")).toBeTruthy();

    await fireEvent.press(screen.getByText('Light'));

    expect(useThemePrefs.getState().mode).toBe('light');
    expect(screen.getByText('Always light.')).toBeTruthy();
  });
});
