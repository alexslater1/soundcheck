import { fireEvent, render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';
import { useThemePrefs } from '@/theme/themePrefs';

import { AppearanceScreen } from './AppearanceScreen';

describe('AppearanceScreen', () => {
  beforeEach(() =>
    useThemePrefs.setState({ themeId: 'electric', mode: 'auto', typeStyle: 'grotesk', glow: true }),
  );

  async function renderScreen() {
    await render(
      <ThemeProvider>
        <AppearanceScreen />
      </ThemeProvider>,
    );
  }

  it('switches between auto, dark and light', async () => {
    await renderScreen();
    expect(screen.getByText("Follows the phone's dark or light setting.")).toBeTruthy();

    await fireEvent.press(screen.getByText('Light'));

    expect(useThemePrefs.getState().mode).toBe('light');
    expect(screen.getByText('Always light.')).toBeTruthy();
  });

  it('switches the typeface and the glow, and the preview follows', async () => {
    await renderScreen();
    expect(screen.getByText('Gigs this weekend')).toHaveStyle({
      fontFamily: 'SpaceGrotesk_700Bold',
    });
    expect(screen.getAllByTestId('screen-glow')).toHaveLength(1);

    await fireEvent.press(screen.getByText('System'));
    await fireEvent.press(screen.getByText('Off'));

    expect(useThemePrefs.getState()).toMatchObject({ typeStyle: 'system', glow: false });
    expect(screen.getByText('Gigs this weekend')).not.toHaveStyle({
      fontFamily: 'SpaceGrotesk_700Bold',
    });
    expect(screen.queryByTestId('screen-glow')).toBeNull();
  });
});
