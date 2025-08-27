import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { Platform, Text } from 'react-native';

import type { MeResponse } from '@/api/generated/model';
import { useAuthStore } from '@/features/auth/store';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { ConsoleGate } from './ConsoleGate';

// The real shell needs the router; the gate only decides whether it is mounted.
jest.mock('./lazy', () => {
  const { View } = jest.requireActual<typeof import('react-native')>('react-native');
  const React = jest.requireActual<typeof import('react')>('react');
  return {
    LazyConsoleShell: ({ children }: { children: React.ReactNode }) =>
      React.createElement(View, { testID: 'console-shell' }, children),
  };
});

let mockWidth = 1440;
jest.mock('react-native/Libraries/Utilities/useWindowDimensions', () => ({
  __esModule: true,
  default: () => ({ width: mockWidth, height: 900, scale: 1, fontScale: 1 }),
}));

/** Stands in for a console page: if it renders, the page's admin requests would be sent. */
const Page = () => <Text testID="admin-page">page</Text>;

function signIn(role: string | undefined, handle = 'someone') {
  useAuthStore.setState({
    status: role === undefined ? 'signedOut' : 'signedIn',
    me: role === undefined ? null : ({ role, handle } as MeResponse),
  });
}

async function renderGate() {
  await render(
    <ThemeProvider>
      <ConsoleGate>
        <Page />
      </ConsoleGate>
    </ThemeProvider>,
  );
}

describe('ConsoleGate', () => {
  beforeEach(() => {
    jest.replaceProperty(Platform, 'OS', 'web');
    mockWidth = 1440;
  });

  afterEach(async () => {
    jest.restoreAllMocks();
    await act(async () => {
      useAuthStore.setState({ status: 'unknown', me: null });
    });
  });

  it('shows an admin the shell with the page inside', async () => {
    signIn('ADMIN');
    await renderGate();
    expect(await screen.findByTestId('console-shell')).toBeOnTheScreen();
    expect(screen.getByTestId('admin-page')).toBeOnTheScreen();
  });

  it('tells a USER the area is for operators and never mounts the page', async () => {
    signIn('USER');
    await renderGate();
    expect(screen.getByText('This area is for operators')).toBeOnTheScreen();
    expect(screen.queryByTestId('admin-page')).toBeNull();
    expect(screen.queryByTestId('console-shell')).toBeNull();
  });

  it("lets a USER sign out to switch to the admin account, naming who's signed in", async () => {
    const signOut = jest.fn(() => Promise.resolve());
    const realSignOut = useAuthStore.getState().signOut;
    signIn('USER', 'alex1223');
    useAuthStore.setState({ signOut });
    await renderGate();
    expect(screen.getByText(/signed in as @alex1223/)).toBeOnTheScreen();
    await act(async () => {
      fireEvent.press(screen.getByText('Sign out and sign in as admin'));
    });
    expect(signOut).toHaveBeenCalledTimes(1);
    useAuthStore.setState({ signOut: realSignOut });
  });

  it('waits while signed out (the root layout is sending the user to sign-in)', async () => {
    signIn(undefined);
    await renderGate();
    expect(screen.getByTestId('console-loading')).toBeOnTheScreen();
    expect(screen.queryByTestId('admin-page')).toBeNull();
  });

  it('asks an admin on a phone browser for a wider screen', async () => {
    signIn('ADMIN');
    mockWidth = 412;
    await renderGate();
    expect(screen.getByText('Open the console on a wider screen')).toBeOnTheScreen();
    expect(screen.queryByTestId('admin-page')).toBeNull();
  });

  it('points the Android app to the desktop', async () => {
    jest.replaceProperty(Platform, 'OS', 'android');
    signIn('ADMIN');
    await renderGate();
    expect(screen.getByTestId('console-native')).toBeOnTheScreen();
    expect(screen.queryByTestId('admin-page')).toBeNull();
  });
});
