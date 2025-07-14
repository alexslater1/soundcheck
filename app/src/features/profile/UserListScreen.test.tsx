import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren, ReactNode } from 'react';

import { useGetUserByHandle } from '@/api/generated/me/me';
import { removeFollower, useListFollowers, useListFollowing } from '@/api/generated/social/social';
import { useAuthStore } from '@/features/auth/store';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { UserListScreen } from './UserListScreen';

jest.mock('expo-router', () => ({
  Link: ({ children }: { children: ReactNode }) => children,
}));
jest.mock('@/api/generated/me/me', () => ({
  useGetUserByHandle: jest.fn(),
  getGetUserByHandleQueryKey: (handle: string) => ['user', handle],
}));
jest.mock('@/api/generated/social/social', () => ({
  useListFollowers: jest.fn(),
  useListFollowing: jest.fn(),
  removeFollower: jest.fn(),
}));

const mockProfile = jest.mocked(useGetUserByHandle);
const mockFollowers = jest.mocked(useListFollowers);
const mockFollowing = jest.mocked(useListFollowing);
const mockRemove = jest.mocked(removeFollower);

function Providers({ children }: PropsWithChildren) {
  return (
    <QueryClientProvider client={new QueryClient()}>
      <ThemeProvider>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

const list = {
  isPending: false,
  data: {
    items: [
      { id: 'u2', handle: 'maya', displayName: 'Maya', avatarUrl: null, isFollowing: false },
      { id: 'u3', handle: 'theo', displayName: 'Theo', avatarUrl: null, isFollowing: true },
    ],
    nextCursor: null,
  },
};

beforeEach(() => {
  jest.clearAllMocks();
  useAuthStore.setState({ me: { id: 'me' } as never });
  mockFollowers.mockReturnValue(list as never);
  mockFollowing.mockReturnValue(list as never);
});

describe('UserListScreen: Remove follower', () => {
  it('lets you remove someone from your own followers', async () => {
    mockProfile.mockReturnValue({ isPending: false, data: { id: 'me' } } as never);
    mockRemove.mockResolvedValue(undefined);
    await render(<UserListScreen handle="me_handle" kind="followers" />, { wrapper: Providers });

    await fireEvent.press(screen.getByLabelText('Remove @maya from your followers'));

    expect(mockRemove).toHaveBeenCalledWith('u2');
    await waitFor(() => expect(screen.queryByText('@maya')).toBeNull());
    expect(screen.getByText('@theo')).toBeOnTheScreen();
  });

  it('puts the row back when removing fails', async () => {
    mockProfile.mockReturnValue({ isPending: false, data: { id: 'me' } } as never);
    mockRemove.mockRejectedValue(new Error('offline'));
    await render(<UserListScreen handle="me_handle" kind="followers" />, { wrapper: Providers });

    await fireEvent.press(screen.getByLabelText('Remove @maya from your followers'));

    await waitFor(() => expect(screen.getByText('Try again')).toBeOnTheScreen());
    expect(screen.getByText('@maya')).toBeOnTheScreen();
  });

  it("offers no Remove on someone else's list or on your Following list", async () => {
    mockProfile.mockReturnValue({ isPending: false, data: { id: 'someone' } } as never);
    const { rerender } = await render(<UserListScreen handle="ben" kind="followers" />, {
      wrapper: Providers,
    });
    expect(screen.queryByTestId('remove-follower')).toBeNull();

    mockProfile.mockReturnValue({ isPending: false, data: { id: 'me' } } as never);
    await rerender(<UserListScreen handle="me_handle" kind="following" />);
    expect(screen.queryByTestId('remove-follower')).toBeNull();
  });
});
