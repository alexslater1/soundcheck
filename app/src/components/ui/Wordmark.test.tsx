import { render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';
import { useThemePrefs } from '@/theme/themePrefs';

import markDark from '../../../assets/mark.png';
import markLight from '../../../assets/mark-light.png';

import { Wordmark } from './Wordmark';

afterEach(() => useThemePrefs.setState({ mode: 'auto' }));

// The two marks are different files (asset ids under Jest), so the test can tell which one is shown.
describe('Wordmark', () => {
  it('has a mark for each palette', () => {
    expect(markDark).not.toEqual(markLight);
  });

  it.each(['dark', 'light'] as const)(
    'shows the mic-check mark drawn in the %s palette beside the name',
    async (mode) => {
      useThemePrefs.setState({ mode });
      await render(
        <ThemeProvider>
          <Wordmark size={30} />
        </ThemeProvider>,
      );

      expect(screen.getByRole('header', { name: 'Soundcheck' })).toBeOnTheScreen();
      const mark = screen.getByTestId('wordmark-mark');
      expect(mark.props.source).toBe(mode === 'dark' ? markDark : markLight);
      expect(mark.props.style).toEqual({ width: 35, height: 35 });
      expect(screen.getByText('Soundcheck')).toBeOnTheScreen();
    },
  );
});
