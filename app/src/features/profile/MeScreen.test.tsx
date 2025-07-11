import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import { useAuthStore } from '@/features/auth/store';
import { useUploadQueue } from '@/features/upload/uploadQueue';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { MeScreen } from './MeScreen';

const mockRefetch = jest.fn(async () => undefined);
const mockPosts = jest.fn();
const mockPush = jest.fn();
const mockChangePhoto = jest.fn(async () => undefined);
let client: QueryClient;

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
// The gigs calendar and the stats have their own tests and their own data.
jest.mock('./MyGigsCalendar', () => {
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  return { MyGigsCalendar: () => <Text testID="my-gigs">calendar</Text> };
});
jest.mock('@/features/stats/GigStatsSection', () => {
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  return { GigStatsSection: () => <Text testID="stats">stats</Text> };
});
jest.mock('./useChangeAvatar', () => ({
  useChangeAvatar: () => ({ change: mockChangePhoto, saving: false, error: null }),
}));
jest.mock('@/api/generated/posts/posts', () => ({
  useListMyPosts: (...args: unknown[]) => mockPosts(...args),
}));
jest.mock('@/features/posts/PostGrid', () => {
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    PostGrid: ({ posts }: { posts: unknown[] }) => <Text testID="grid">{posts.length}</Text>,
  };
});
jest.mock('expo-router', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  return {
    Link: ({ children }: { children: React.ReactNode }) =>
      React.createElement(React.Fragment, null, children),
    useRouter: () => ({ push: mockPush }),
    // Focus happens once, on mount.
    useFocusEffect: (effect: () => void) => React.useEffect(effect, [effect]),
  };
});

function Providers({ children }: PropsWithChildren) {
  return (
    <QueryClientProvider client={client}>
      <ThemeProvider>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

const me = {
  id: 'user-1',
  handle: 'anna',
  displayName: 'Anna',
  bio: null,
  homeCity: { id: 1, name: 'Manchester' },
  genres: [{ genreId: 1, weight: 1 }],
  counts: { posts: 0, followers: 0, following: 0 },
};
const video = {
  uri: 'blob:clip',
  fileName: 'clip.mp4',
  contentType: 'video/mp4',
  bytes: 1000,
  durationMs: 5000,
  width: null,
  height: null,
};
const posts = (...items: { id: string; status: string }[]) => ({
  isPending: false,
  data: { items },
  refetch: mockRefetch,
});

describe('MeScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    client = new QueryClient({ defaultOptions: { queries: { gcTime: Infinity } } });
    useUploadQueue.setState({ entries: [] });
    useAuthStore.setState({ me: me as never, reloadMe: jest.fn(async () => undefined) });
    mockPosts.mockReturnValue(posts());
  });

  it('says a clip is on its way while its post does not exist yet, and refetches the grid when it does', async () => {
    const id = useUploadQueue.getState().enqueue('user-1', video, { platform: 'web' });
    useUploadQueue.getState().patch(id, { draft: { caption: '', gigId: null, artistIds: [] } });
    await render(<MeScreen />, { wrapper: Providers });

    expect(screen.getByTestId('me-uploading')).toBeTruthy();
    expect(screen.queryByTestId('me-no-posts')).toBeNull();
    const fetchesBefore = mockRefetch.mock.calls.length;

    // The upload completes in the background and the post is created.
    await act(async () => useUploadQueue.getState().patch(id, { postId: 'post-1' }));

    await waitFor(() => expect(mockRefetch.mock.calls.length).toBeGreaterThan(fetchesBefore));
    expect(screen.queryByTestId('me-uploading')).toBeNull();
  });

  it('shows the empty state when there is nothing posted and nothing on its way', async () => {
    await render(<MeScreen />, { wrapper: Providers });
    expect(screen.getByTestId('me-no-posts')).toBeTruthy();
  });

  it('has three tabs, Posts first, then My gigs and Stats', async () => {
    mockPosts.mockReturnValue(posts({ id: 'p1', status: 'PUBLISHED' }));
    await render(<MeScreen />, { wrapper: Providers });
    expect(screen.getByTestId('profile-tab-posts')).toBeSelected();
    expect(screen.getByTestId('grid')).toHaveTextContent('1');
    expect(screen.queryByTestId('my-gigs')).toBeNull();
    expect(screen.queryByTestId('stats')).toBeNull();

    await fireEvent.press(screen.getByText('MY GIGS'));
    expect(screen.getByTestId('profile-tab-gigs')).toBeSelected();
    expect(screen.getByTestId('my-gigs')).toBeTruthy();
    expect(screen.queryByTestId('grid')).toBeNull();

    await fireEvent.press(screen.getByText('STATS'));
    expect(screen.getByTestId('stats')).toBeTruthy();
    expect(screen.getByText(/Your profile is public/)).toBeTruthy();
  });

  it('opens Settings from the gear, and edits from the photo, the bio, the city and the genres', async () => {
    await render(<MeScreen />, { wrapper: Providers });

    await fireEvent.press(screen.getByLabelText('Settings'));
    expect(mockPush).toHaveBeenLastCalledWith('/settings');
    await fireEvent.press(screen.getByLabelText('Add a profile photo'));
    expect(mockChangePhoto).toHaveBeenCalledTimes(1);
    await fireEvent.press(screen.getByLabelText('Add a bio'));
    expect(mockPush).toHaveBeenLastCalledWith('/settings/profile');
    expect(screen.getByText('Manchester')).toBeTruthy();
    expect(screen.getByText('1 genre')).toBeTruthy();
  });

  it('asks again for its stats and gigs when a post goes live or goes, not on the first load', async () => {
    const stats = ['/api/v1/users/user-1/stats', { top: 10 }];
    const calendar = ['/api/v1/me/gigs', { from: 'a', to: 'b', limit: 50 }];
    client.setQueryData(stats, {});
    client.setQueryData(calendar, {});
    mockPosts.mockReturnValue(posts({ id: 'p1', status: 'PENDING_MEDIA' }));
    const view = await render(<MeScreen />, { wrapper: Providers });
    expect(client.getQueryState(stats)?.isInvalidated).toBe(false);

    // The clip was transcoded: the post is live, and its gig now counts as Went.
    mockPosts.mockReturnValue(posts({ id: 'p1', status: 'PUBLISHED' }));
    await view.rerender(<MeScreen />);

    await waitFor(() => expect(client.getQueryState(stats)?.isInvalidated).toBe(true));
    expect(client.getQueryState(calendar)?.isInvalidated).toBe(true);
  });

  it('a profile reload that fails in the background leaves no unhandled rejection', async () => {
    const unhandled: unknown[] = [];
    const onUnhandled = (reason: unknown) => unhandled.push(reason);
    process.on('unhandledRejection', onUnhandled);
    useAuthStore.setState({
      reloadMe: jest.fn(async () => {
        throw new Error('Authentication required');
      }),
    });
    try {
      await render(<MeScreen />, { wrapper: Providers });
      // Unhandled rejections are reported after the microtask queue drains.
      await new Promise((resolve) => setTimeout(resolve, 10));
    } finally {
      process.off('unhandledRejection', onUnhandled);
    }

    expect(unhandled).toEqual([]);
    // The profile already on screen stays.
    expect(screen.getByText('Anna')).toBeTruthy();
  });
});
