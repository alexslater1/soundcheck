import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import { followUser, unfollowUser } from '@/api/generated/social/social';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { FollowUserButton, nextFollowState } from './FollowUserButton';

jest.mock('@/api/generated/social/social', () => ({
  followUser: jest.fn(),
  unfollowUser: jest.fn(),
}));

const mockFollow = jest.mocked(followUser);
const mockUnfollow = jest.mocked(unfollowUser);

function button(props: Partial<Parameters<typeof FollowUserButton>[0]> = {}) {
  return (
    <ThemeProvider>
      <FollowUserButton
        userId="u2"
        handle="ben"
        isFollowing={false}
        isRequested={false}
        isPrivate={false}
        {...props}
      />
    </ThemeProvider>
  );
}

beforeEach(() => jest.clearAllMocks());

describe('nextFollowState', () => {
  it('follows a public profile, asks a private one, and undoes either', () => {
    expect(nextFollowState('none', false)).toBe('following');
    expect(nextFollowState('none', true)).toBe('requested');
    expect(nextFollowState('following', true)).toBe('none');
    expect(nextFollowState('requested', true)).toBe('none');
  });
});

describe('FollowUserButton', () => {
  it('asks to follow a private profile and shows Requested', async () => {
    mockFollow.mockResolvedValue({ state: 'REQUESTED' });
    await render(button({ isPrivate: true }));

    await fireEvent.press(screen.getByLabelText('Ask to follow ben'));

    await waitFor(() => expect(screen.getByText('Requested')).toBeOnTheScreen());
    expect(mockFollow).toHaveBeenCalledWith('u2');
  });

  it('believes the server: a profile that turned public is followed at once', async () => {
    mockFollow.mockResolvedValue({ state: 'FOLLOWING' });
    await render(button({ isPrivate: true }));

    await fireEvent.press(screen.getByTestId('follow-user'));

    await waitFor(() => expect(screen.getByText('Following')).toBeOnTheScreen());
  });

  it('cancels a waiting request', async () => {
    mockUnfollow.mockResolvedValue(undefined);
    await render(button({ isPrivate: true, isRequested: true }));

    await fireEvent.press(screen.getByLabelText('Cancel your follow request to ben'));

    await waitFor(() => expect(screen.getByText('Follow')).toBeOnTheScreen());
    expect(mockUnfollow).toHaveBeenCalledWith('u2');
  });

  it('puts the label back when the request fails', async () => {
    mockFollow.mockRejectedValue(new Error('offline'));
    await render(button());

    await fireEvent.press(screen.getByLabelText('Follow ben'));

    await waitFor(() => expect(screen.getByText('Try again')).toBeOnTheScreen());
    expect(screen.getByLabelText('Follow ben')).toBeOnTheScreen();
  });
});
