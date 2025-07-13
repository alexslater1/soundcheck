import { fireEvent, render, screen } from '@testing-library/react-native';
import type { ReactNode } from 'react';
import { StyleSheet, Text } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { ThemeProvider } from '@/theme/ThemeProvider';
import { themes } from '@/theme/tokens';

import { PageHeader, pageStackOptions, type PageHeaderProps } from './PageHeader';

// Icon fonts are loaded through expo-asset, which is not available under Jest.
jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

const metrics = {
  frame: { x: 0, y: 0, width: 412, height: 915 },
  insets: { top: 24, left: 0, right: 0, bottom: 0 },
};

function Providers({ children }: { children: ReactNode }) {
  return (
    <SafeAreaProvider initialMetrics={metrics}>
      <ThemeProvider>{children}</ThemeProvider>
    </SafeAreaProvider>
  );
}

async function renderHeader(props: Partial<PageHeaderProps>) {
  const navigation = { goBack: jest.fn() };
  await render(
    <Providers>
      <PageHeader options={{ title: 'Privacy' }} navigation={navigation} {...props} />
    </Providers>,
  );
  return { navigation };
}

describe('PageHeader', () => {
  it('shows a back chevron that goes back, and the title as a heading in the display face', async () => {
    const { navigation } = await renderHeader({ back: { title: 'Settings' } });
    await screen.findByTestId('page-header');

    await fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    expect(navigation.goBack).toHaveBeenCalledTimes(1);
    const title = screen.getByRole('header', { name: 'Privacy' });
    expect(StyleSheet.flatten(title.props.style).fontFamily).toBe('SpaceGrotesk_700Bold');
  });

  it('has no grey bar: nothing behind it but the page, and room for the status bar', async () => {
    await renderHeader({ back: { title: 'Settings' } });
    const header = await screen.findByTestId('page-header');

    const style = StyleSheet.flatten(header.props.style);
    expect(style.backgroundColor).toBeUndefined();
    expect(style.paddingTop).toBe(24);
  });

  it('shows no chevron when there is nothing to go back to', async () => {
    await renderHeader({});
    await screen.findByTestId('page-header');

    expect(screen.queryByRole('button', { name: 'Back' })).toBeNull();
    expect(screen.getByRole('header', { name: 'Privacy' })).toBeOnTheScreen();
  });

  it('shows only the chevron when the title is empty (the page has its own heading)', async () => {
    await renderHeader({ back: { title: 'Profile' }, options: { title: '' } });
    await screen.findByTestId('page-header');

    expect(screen.getByRole('button', { name: 'Back' })).toBeOnTheScreen();
    expect(screen.queryByText('Privacy')).toBeNull();
  });

  it('shows what a screen puts on its right', async () => {
    await renderHeader({ options: { title: 'Me', headerRight: () => <Text>Gear</Text> } });

    expect(await screen.findByText('Gear')).toBeOnTheScreen();
  });
});

describe('pageStackOptions', () => {
  it('draws every stack header with PageHeader, transparent over the theme background', async () => {
    const colors = themes.electric.palettes.dark;
    const options = pageStackOptions(colors);
    expect(options.headerTransparent).toBe(true);
    expect(options.contentStyle).toEqual({ backgroundColor: colors.background });

    const header = options.header({
      back: { title: 'Gig' },
      options: { title: 'Venue' },
      navigation: { goBack: jest.fn() },
    });
    await render(<Providers>{header}</Providers>);
    expect(await screen.findByRole('header', { name: 'Venue' })).toBeOnTheScreen();
  });
});
