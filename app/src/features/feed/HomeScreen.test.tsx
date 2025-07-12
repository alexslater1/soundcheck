import { act, fireEvent, render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { HomeScreen } from './HomeScreen';

// Each feed records how often it was mounted and what it was last told.
const mockMounts = { forYou: 0, following: 0 };
const mockLast: Record<string, { visible?: boolean; refreshToken?: number }> = {};

function mockFeed(name: 'forYou' | 'following') {
  const React = jest.requireActual<typeof import('react')>('react');
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  return function Feed(props: { visible?: boolean; refreshToken?: number }) {
    React.useEffect(() => {
      mockMounts[name] += 1;
    }, []);
    mockLast[name] = props;
    return React.createElement(Text, null, `${name} ${props.visible ? 'shown' : 'hidden'}`);
  };
}

jest.mock('./ForYouFeed', () => ({ ForYouFeed: mockFeed('forYou') }));
jest.mock('./FollowingFeed', () => ({ FollowingFeed: mockFeed('following') }));
const mockReselect: (() => void)[] = [];
jest.mock('@/lib/useTabReselect', () => ({
  useTabReselect: (callback: () => void) => {
    mockReselect[0] = callback;
  },
}));

describe('HomeScreen', () => {
  beforeEach(() => {
    mockMounts.forYou = 0;
    mockMounts.following = 0;
  });

  it('keeps both feeds mounted when switching, so each keeps its place', async () => {
    await render(
      <ThemeProvider>
        <HomeScreen />
      </ThemeProvider>,
    );
    // Following is not fetched until it is first opened.
    expect(screen.getByText('forYou shown')).toBeTruthy();
    expect(mockMounts.following).toBe(0);

    await fireEvent.press(screen.getByText('Following'));
    expect(screen.getByText('following shown')).toBeTruthy();
    // The hidden feed is out of reach of screen readers too.
    expect(screen.queryByText('forYou hidden')).toBeNull();
    expect(screen.getByText('forYou hidden', { includeHiddenElements: true })).toBeTruthy();

    await fireEvent.press(screen.getByText('For You'));
    await fireEvent.press(screen.getByText('Following'));

    // Never unmounted, never mounted twice: no reload and no lost position.
    expect(mockMounts).toEqual({ forYou: 1, following: 1 });
    expect(
      screen.getByTestId('feed-layer-for-you', { includeHiddenElements: true }).props.pointerEvents,
    ).toBe('none');
    expect(screen.getByTestId('feed-layer-following').props.pointerEvents).toBe('auto');
    // Switching is not a refresh.
    expect(mockLast.forYou?.refreshToken).toBe(0);
    expect(mockLast.following?.refreshToken).toBe(0);
  });

  it('refreshes only the open feed when its chip or the Home tab is pressed again', async () => {
    await render(
      <ThemeProvider>
        <HomeScreen />
      </ThemeProvider>,
    );
    await fireEvent.press(screen.getByText('For You'));
    expect(mockLast.forYou?.refreshToken).toBe(1);

    await fireEvent.press(screen.getByText('Following'));
    await fireEvent.press(screen.getByText('Following'));
    expect(mockLast.following?.refreshToken).toBe(1);

    // The Home tab pressed while Home shows Following.
    await act(async () => mockReselect[0]?.());
    expect(mockLast.following?.refreshToken).toBe(2);
    expect(mockLast.forYou?.refreshToken).toBe(1);
  });
});
