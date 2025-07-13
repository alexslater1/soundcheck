import { act, fireEvent, renderRouter, screen } from 'expo-router/testing-library';
import { router } from 'expo-router';
import { StyleSheet, Text } from 'react-native';

import AuthLayout from '@/app/(auth)/_layout';
import SettingsLayout from '@/app/settings/_layout';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { Screen } from './Screen';

// The real auth and settings layouts in expo-router's test router. (This file lives here, not in
// src/app: everything under src/app is a route, and a test there would be bundled into the app.)
// Icon fonts are loaded through expo-asset, which is not available under Jest.
jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

/** A page that is only a Screen with one line, so the test sees the real header and the real column. */
const page = (text: string) =>
  function Page() {
    return (
      <Screen testID={`column-${text}`}>
        <Text>{text}</Text>
      </Screen>
    );
  };

const themed = (Layout: () => React.JSX.Element) =>
  function Root() {
    return (
      <ThemeProvider>
        <Layout />
      </ThemeProvider>
    );
  };

describe('stack headers', () => {
  it('sign-in has no header; sign-up has the back chevron and leaves the title to the page', async () => {
    await renderRouter(
      {
        _layout: themed(AuthLayout),
        'sign-in': page('Welcome back'),
        'sign-up': page('Create account'),
      },
      { initialUrl: '/sign-in' },
    );
    expect(await screen.findByText('Welcome back')).toBeOnTheScreen();
    expect(screen.queryByTestId('page-header')).toBeNull();

    await act(() => router.push('/sign-up'));
    expect(await screen.findByText('Create account')).toBeOnTheScreen();
    expect(screen.getByTestId('page-header')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Back' })).toBeOnTheScreen();
    expect(screen.queryByRole('header', { name: 'Create account' })).toBeNull();
  });

  it('a settings page has the page header with its title, and its content starts below it', async () => {
    await renderRouter(
      {
        'settings/_layout': themed(SettingsLayout),
        'settings/index': page('Hub'),
        'settings/privacy': page('Who sees your stats'),
        ...Object.fromEntries(
          ['profile', 'account', 'appearance', 'genres'].map((name) => [
            `settings/${name}`,
            page(name),
          ]),
        ),
      },
      { initialUrl: '/settings' },
    );
    expect(await screen.findByRole('header', { name: 'Settings' })).toBeOnTheScreen();

    await act(() => router.push('/settings/privacy'));
    expect(await screen.findByRole('header', { name: 'Privacy' })).toBeOnTheScreen();
    const column = StyleSheet.flatten(screen.getByTestId('column-Who sees your stats').props.style);
    expect(column.paddingTop).toBeGreaterThan(0);

    await fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    expect(await screen.findByRole('header', { name: 'Settings' })).toBeOnTheScreen();
  });
});
