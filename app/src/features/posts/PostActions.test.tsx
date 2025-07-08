import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import type { Post, Reason } from '@/api/generated/model';
import { useAuthStore } from '@/features/auth/store';
import { telemetry } from '@/features/telemetry/telemetry';
import { TelemetryScope } from '@/features/telemetry/TelemetryScope';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { PostActions } from './PostActions';

const mockHide = jest.fn(async (_id: string) => undefined);
const mockReport = jest.fn(async (_id: string, _body: unknown) => undefined);
const mockDelete = jest.fn(async (_id: string) => undefined);
const mockShare = jest.fn(async (_id: string) => 'copied');

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
jest.mock('expo-router', () => ({ useRouter: () => ({ push: jest.fn() }) }));
jest.mock('@/api/generated/engagement/engagement', () => ({
  hidePost: (id: string) => mockHide(id),
  reportPost: (id: string, body: unknown) => mockReport(id, body),
  likePost: jest.fn(),
  unlikePost: jest.fn(),
}));
jest.mock('@/api/generated/posts/posts', () => ({ deletePost: (id: string) => mockDelete(id) }));
jest.mock('./sharePost', () => ({ sharePost: (id: string) => mockShare(id) }));

const post = {
  id: 'post-1',
  author: { id: 'author-1', handle: 'maya', displayName: 'Maya' },
  status: 'PUBLISHED',
  likeCount: 3,
  commentCount: 1,
  likedByMe: false,
} as Post;
const reasons: Reason[] = [
  { kind: 'FOLLOWS_AUTHOR', text: 'You follow @maya' },
  { kind: 'WENT_TO_GIG', text: 'From a gig you went to' },
];

function renderActions(onGone = jest.fn(), withReasons = true) {
  return render(
    <ThemeProvider>
      <TelemetryScope place={{ surface: 'FOR_YOU', sessionId: 'session-1', position: 4 }}>
        <PostActions post={post} reasons={withReasons ? reasons : undefined} onGone={onGone} />
      </TelemetryScope>
    </ThemeProvider>,
  );
}

describe('PostActions', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useAuthStore.setState({ me: { id: 'viewer-1' } as never });
  });

  it('explains why the feed shows the post', async () => {
    await renderActions();
    expect(screen.queryByTestId('why-this-post')).toBeNull();

    await fireEvent.press(screen.getByLabelText('More'));
    await fireEvent.press(screen.getByText('Why this post?'));

    expect(screen.getByTestId('why-this-post')).toBeTruthy();
    expect(screen.getByText('You follow @maya')).toBeTruthy();
    expect(screen.getByText('From a gig you went to')).toBeTruthy();
    await fireEvent.press(screen.getByText('Close'));
    expect(screen.queryByTestId('why-this-post')).toBeNull();
  });

  it('has no "Why this post?" where there are no reasons (the Following feed, a post page)', async () => {
    await renderActions(jest.fn(), false);

    await fireEvent.press(screen.getByLabelText('More'));

    expect(screen.queryByText('Why this post?')).toBeNull();
    expect(screen.getByText('Show me less like this')).toBeTruthy();
  });

  it('hides the post, tells the list, and reports HIDE with the place it happened in', async () => {
    const onGone = jest.fn();
    await renderActions(onGone);

    await fireEvent.press(screen.getByLabelText('More'));
    await fireEvent.press(screen.getByText('Show me less like this'));

    await waitFor(() => expect(onGone).toHaveBeenCalledWith('post-1'));
    expect(mockHide).toHaveBeenCalledWith('post-1');
    expect(telemetry.track).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'HIDE',
        postId: 'post-1',
        surface: 'FOR_YOU',
        sessionId: 'session-1',
        position: 4,
      }),
    );
  });

  it('reports nothing when the request fails: a HIDE event would hide the post on the server anyway', async () => {
    mockHide.mockRejectedValueOnce(new Error('offline'));
    mockShare.mockResolvedValueOnce('failed');
    const onGone = jest.fn();
    await renderActions(onGone);

    await fireEvent.press(screen.getByLabelText('More'));
    await fireEvent.press(screen.getByText('Show me less like this'));
    await waitFor(() =>
      expect(screen.getByTestId('post-notice').props.children).toBe(
        'That did not work. Try again.',
      ),
    );
    await fireEvent.press(screen.getByLabelText('Share'));
    await waitFor(() =>
      expect(screen.getByTestId('post-notice').props.children).toBe('Could not share'),
    );

    expect(onGone).not.toHaveBeenCalled();
    expect(telemetry.track).not.toHaveBeenCalled();
  });

  it('reports a share and a report', async () => {
    await renderActions();

    await fireEvent.press(screen.getByLabelText('Share'));
    await waitFor(() =>
      expect(screen.getByTestId('post-notice').props.children).toBe('Link copied'),
    );
    await fireEvent.press(screen.getByLabelText('More'));
    await fireEvent.press(screen.getByText('Report'));
    await waitFor(() => expect(mockReport).toHaveBeenCalledWith('post-1', { reason: 'OTHER' }));

    const types = (telemetry.track as jest.Mock).mock.calls.map((call) => call[0].type);
    expect(types).toEqual(['SHARE', 'REPORT']);
  });

  it('offers the author Delete, and nothing else, in the menu', async () => {
    useAuthStore.setState({ me: { id: 'author-1' } as never });
    const onGone = jest.fn();
    await renderActions(onGone);

    await fireEvent.press(screen.getByLabelText('More'));

    expect(screen.queryByText('Show me less like this')).toBeNull();
    expect(screen.queryByText('Why this post?')).toBeNull();
    await fireEvent.press(screen.getByText('Delete post'));
    // A confirm step before anything is deleted.
    expect(screen.getByText('Delete this post?')).toBeOnTheScreen();
    expect(mockDelete).not.toHaveBeenCalled();
    await fireEvent.press(screen.getByText('Delete'));
    await waitFor(() => expect(onGone).toHaveBeenCalledWith('post-1'));
    expect(mockDelete).toHaveBeenCalledWith('post-1');
    expect(screen.queryByTestId('post-menu')).toBeNull();
  });

  it('gives the author of a failed post the same small menu, not a big Delete button', async () => {
    useAuthStore.setState({ me: { id: 'author-1' } as never });
    await render(
      <ThemeProvider>
        <PostActions post={{ ...post, status: 'FAILED' } as Post} />
      </ThemeProvider>,
    );
    expect(screen.queryByText('Delete post')).toBeNull();
    expect(screen.queryByLabelText('Share')).toBeNull();

    await fireEvent.press(screen.getByLabelText('More'));
    await fireEvent.press(screen.getByText('Delete post'));
    await fireEvent.press(screen.getByText('Delete'));

    await waitFor(() => expect(mockDelete).toHaveBeenCalledWith('post-1'));
  });
});
