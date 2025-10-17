import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import { useAuthStore } from '@/features/auth/store';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { useProfileOrder } from './profileOrder';
import { ProfileScreen } from './ProfileScreen';

const mockProfile = jest.fn();
const mockPosts = jest.fn();
const mockStats = jest.fn((_props: unknown) => null);
const mockGigs = jest.fn((_props: unknown) => null);

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
jest.mock('@/api/generated/me/me', () => ({
  useGetUserByHandle: (...args: unknown[]) => mockProfile(...args),
  getGetUserByHandleQueryKey: (handle: string) => ['/api/v1/users', handle],
}));
jest.mock('@/api/generated/posts/posts', () => ({
  useListUserPosts: (...args: unknown[]) => mockPosts(...args),
}));
jest.mock('@/api/generated/social/social', () => ({
  blockUser: jest.fn(),
  unblockUser: jest.fn(),
}));
jest.mock('./FollowUserButton', () => ({ FollowUserButton: () => null }));
jest.mock('./UserGigs', () => ({ UserGigs: (props: unknown) => mockGigs(props) }));
jest.mock('@/features/stats/GigStatsSection', () => ({
  GigStatsSection: (props: unknown) => mockStats(props),
}));
jest.mock('@/features/posts/PostGrid', () => {
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  const React = jest.requireActual<typeof import('react')>('react');
  return {
    PostGrid: ({ posts }: { posts: unknown[] }) =>
      React.createElement(Text, { testID: 'post-grid' }, `${posts.length} clips`),
  };
});

function Providers({ children }: PropsWithChildren) {
  return (
    <QueryClientProvider client={new QueryClient()}>
      <ThemeProvider>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

const profile = {
  id: 'user-2',
  handle: 'ben',
  displayName: 'Ben',
  avatarUrl: null,
  bio: null,
  homeCityName: null,
  counts: { posts: 4, followers: 10, following: 3 },
  isFollowing: false,
  isRequested: false,
  isBlocked: false,
  profileVisibility: 'PRIVATE',
  contentVisible: false,
};

describe('ProfileScreen (private profiles)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useAuthStore.setState({ me: { id: 'user-1' } as never });
    useProfileOrder.setState({ order: 'gig' });
    mockPosts.mockReturnValue({ isPending: false, data: { items: [{ id: 'p1' }, { id: 'p2' }] } });
  });

  it('shows a private profile as private, with its counts, and never asks for its clips or stats', async () => {
    mockProfile.mockReturnValue({ isPending: false, data: profile });
    await render(<ProfileScreen handle="ben" />, { wrapper: Providers });

    expect(screen.getByTestId('profile-private')).toHaveTextContent(/This profile is private/);
    expect(screen.getByTestId('profile-private')).toHaveTextContent(
      /Only @ben's followers see their clips/,
    );
    expect(screen.getByText('4')).toBeTruthy(); // the posts count still shows
    expect(mockPosts).not.toHaveBeenCalled();
    expect(mockStats).not.toHaveBeenCalled();
    expect(mockGigs).not.toHaveBeenCalled();
    expect(screen.queryByTestId('profile-tabs')).toBeNull();
    expect(screen.queryByTestId('post-grid')).toBeNull();
    // The follower and following counts show, but the lists do not open.
    expect(screen.getByTestId('followers-count')).toHaveTextContent('10');
    expect(screen.getByTestId('following-count')).toHaveTextContent('3');
    expect(screen.queryByRole('link', { name: '10 followers' })).toBeNull();
    expect(screen.queryByRole('link', { name: '3 following' })).toBeNull();
  });

  it('says the request is waiting after the viewer asked to follow', async () => {
    mockProfile.mockReturnValue({ isPending: false, data: { ...profile, isRequested: true } });
    await render(<ProfileScreen handle="ben" />, { wrapper: Providers });

    expect(screen.getByTestId('profile-private-detail')).toHaveTextContent(
      /You asked to follow @ben. Once they accept/,
    );
  });

  it('shows the clips, gigs and stats in three tabs when the viewer may see them', async () => {
    mockProfile.mockReturnValue({
      isPending: false,
      data: { ...profile, profileVisibility: 'PUBLIC', contentVisible: true },
    });
    await render(<ProfileScreen handle="ben" />, { wrapper: Providers });

    expect(screen.queryByTestId('profile-private')).toBeNull();
    expect(screen.getByTestId('post-grid')).toHaveTextContent('2 clips');
    // Each tab asks for its own data only when it is opened.
    expect(mockGigs).not.toHaveBeenCalled();
    expect(mockStats).not.toHaveBeenCalled();

    await fireEvent.press(screen.getByText('GIGS'));
    expect(mockGigs).toHaveBeenCalledWith({ userId: 'user-2' });
    expect(screen.queryByTestId('post-grid')).toBeNull();

    await fireEvent.press(screen.getByText('STATS'));
    expect(mockStats).toHaveBeenCalledWith(
      expect.objectContaining({ userId: 'user-2', own: false }),
    );
    expect(screen.getByRole('link', { name: '10 followers' })).toBeTruthy();
    expect(screen.getByRole('link', { name: '3 following' })).toBeTruthy();
  });

  it('lists the clips by gig date, or by when they were posted once the viewer chooses that', async () => {
    mockProfile.mockReturnValue({
      isPending: false,
      data: { ...profile, profileVisibility: 'PUBLIC', contentVisible: true },
    });
    await render(<ProfileScreen handle="ben" />, { wrapper: Providers });

    expect(mockPosts).toHaveBeenLastCalledWith('user-2', { limit: 30, sort: 'gig' });
    expect(screen.getByRole('radio', { name: 'Gig date' })).toBeChecked();

    await fireEvent.press(screen.getByRole('radio', { name: 'Recently posted' }));
    expect(mockPosts).toHaveBeenLastCalledWith('user-2', { limit: 30, sort: 'posted' });
    expect(screen.getByRole('radio', { name: 'Recently posted' })).toBeChecked();
    // One choice for every profile, kept for next time.
    expect(useProfileOrder.getState().order).toBe('posted');
  });
});
