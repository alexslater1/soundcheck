import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import type { HomeFeed, Post } from '@/api/generated/model';
import { ApiError } from '@/api/http';
import type { PagerItem } from '@/features/posts/PostPager';
import { telemetry } from '@/features/telemetry/telemetry';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { ForYouFeed } from './ForYouFeed';

const mockGetHomeFeed = jest.fn<Promise<HomeFeed>, [{ limit?: number; cursor?: string }]>();

jest.mock('@/api/generated/feed/feed', () => ({
  getHomeFeed: (params: { limit?: number; cursor?: string }) => mockGetHomeFeed(params),
  getGetHomeFeedQueryKey: () => ['/api/v1/feed/home'],
}));
// The pager itself needs a real list; here it is a list of lines and a "scroll to the end" button.
jest.mock('@/features/posts/PostPager', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { Text, View } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    PostPager: ({
      items,
      onEndReached,
      onGone,
      onRefresh,
      refreshing,
    }: {
      items: PagerItem[];
      onEndReached?: () => void;
      onGone?: (postId: string) => void;
      onRefresh?: () => void;
      refreshing?: boolean;
    }) =>
      React.createElement(
        View,
        { testID: refreshing ? 'pager-refreshing' : 'pager' },
        ...items.map((item) =>
          React.createElement(
            Text,
            { key: item.post.id, onPress: () => onGone?.(item.post.id) },
            `${item.post.id} #${item.position} ${item.sessionId} ${item.reasons?.[0]?.text ?? ''}`,
          ),
        ),
        React.createElement(Text, { onPress: onEndReached }, 'reach-end'),
        React.createElement(Text, { onPress: onRefresh }, 'pull'),
      ),
  };
});

const post = (id: string) => ({ id }) as Post;

function page(sessionId: string, from: number, count: number, more: boolean): HomeFeed {
  return {
    sessionId,
    items: Array.from({ length: count }, (_, i) => ({
      post: post(`${sessionId}-p${from + i}`),
      position: from + i,
      reasons: [{ kind: 'TRENDING', text: 'Trending this week' }],
    })),
    nextCursor: more ? `${sessionId}:${from + count}` : null,
  };
}

function renderFeed(refreshToken = 0) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const tree = (token: number) => (
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <ForYouFeed refreshToken={token} />
      </ThemeProvider>
    </QueryClientProvider>
  );
  return { tree, rendered: render(tree(refreshToken)) };
}

describe('ForYouFeed', () => {
  beforeEach(() => jest.clearAllMocks());

  it('shows the first page with each item’s position, session and reason', async () => {
    mockGetHomeFeed.mockResolvedValueOnce(page('s1', 0, 2, true));
    await renderFeed().rendered;

    expect(await screen.findByText('s1-p0 #0 s1 Trending this week')).toBeTruthy();
    expect(screen.getByText('s1-p1 #1 s1 Trending this week')).toBeTruthy();
    expect(mockGetHomeFeed).toHaveBeenCalledTimes(1);
    expect(mockGetHomeFeed).toHaveBeenCalledWith({ limit: 10, cursor: undefined });
  });

  it('asks for the next page of the same session when the end is reached', async () => {
    mockGetHomeFeed
      .mockResolvedValueOnce(page('s1', 0, 2, true))
      .mockResolvedValueOnce(page('s1', 2, 2, false));
    await renderFeed().rendered;
    await screen.findByText(/s1-p0/);

    await fireEvent.press(screen.getByText('reach-end'));

    expect(await screen.findByText(/s1-p3 #3/)).toBeTruthy();
    expect(mockGetHomeFeed).toHaveBeenLastCalledWith({ limit: 10, cursor: 's1:2' });
    // The last page: reaching the end again asks for nothing.
    await fireEvent.press(screen.getByText('reach-end'));
    expect(mockGetHomeFeed).toHaveBeenCalledTimes(2);
  });

  it('starts again from the first page when the session has expired (410)', async () => {
    mockGetHomeFeed
      .mockResolvedValueOnce(page('old', 0, 2, true))
      .mockRejectedValueOnce(new ApiError(410, 'FEED_SESSION_EXPIRED', 'expired'))
      .mockResolvedValueOnce(page('new', 0, 2, true));
    await renderFeed().rendered;
    await screen.findByText(/old-p0/);

    await fireEvent.press(screen.getByText('reach-end'));

    // No error is shown; the feed is simply a new one.
    expect(await screen.findByText(/new-p0 #0 new/)).toBeTruthy();
    expect(screen.queryByText(/old-p0/)).toBeNull();
    expect(screen.queryByTestId('for-you-error')).toBeNull();
    expect(mockGetHomeFeed).toHaveBeenCalledTimes(3);
    expect(mockGetHomeFeed).toHaveBeenLastCalledWith({ limit: 10, cursor: undefined });
  });

  it('asks for a new session when the Home screen says refresh', async () => {
    mockGetHomeFeed
      .mockResolvedValueOnce(page('s1', 0, 1, false))
      .mockResolvedValueOnce(page('s2', 0, 1, false));
    const { tree, rendered } = renderFeed();
    await rendered;
    await screen.findByText(/s1-p0/);

    await screen.rerender(tree(1));

    expect(await screen.findByText(/s2-p0/)).toBeTruthy();
    // What was seen so far was sent before the server ranked again.
    expect(telemetry.flush).toHaveBeenCalled();
    expect(mockGetHomeFeed).toHaveBeenLastCalledWith({ limit: 10, cursor: undefined });
  });

  it('drops a post that was hidden without asking the server for the page again', async () => {
    mockGetHomeFeed.mockResolvedValueOnce(page('s1', 0, 2, false));
    await renderFeed().rendered;

    await fireEvent.press(await screen.findByText(/s1-p0/));

    await waitFor(() => expect(screen.queryByText(/s1-p0/)).toBeNull());
    expect(screen.getByText(/s1-p1/)).toBeTruthy();
    expect(mockGetHomeFeed).toHaveBeenCalledTimes(1);
  });

  it('on a pull, keeps the old posts on screen and swaps in a new session’s first page', async () => {
    let answer: (feed: HomeFeed) => void = () => undefined;
    mockGetHomeFeed
      .mockResolvedValueOnce(page('s1', 0, 2, true))
      .mockResolvedValueOnce(page('s1', 2, 2, true))
      .mockReturnValueOnce(new Promise((resolve) => (answer = resolve)));
    await renderFeed().rendered;
    await screen.findByText(/s1-p0/);
    await fireEvent.press(screen.getByText('reach-end'));
    await screen.findByText(/s1-p3/);

    await fireEvent.press(screen.getByText('pull'));

    // While the new session loads: the header animates over the posts already there, no skeleton.
    await waitFor(() => expect(screen.getByTestId('pager-refreshing')).toBeTruthy());
    expect(screen.queryByTestId('for-you-loading')).toBeNull();
    expect(screen.getByText(/s1-p0/)).toBeTruthy();
    await waitFor(() => expect(mockGetHomeFeed).toHaveBeenCalledTimes(3));
    // Only page one is asked for, with no cursor: a new session.
    expect(mockGetHomeFeed).toHaveBeenLastCalledWith({ limit: 10, cursor: undefined });
    expect(telemetry.flush).toHaveBeenCalled();

    answer(page('s2', 0, 2, true));
    expect(await screen.findByText(/s2-p0 #0 s2/)).toBeTruthy();
    expect(screen.queryByText(/s1-p/)).toBeNull();
    await waitFor(() => expect(screen.getByTestId('pager')).toBeTruthy(), { timeout: 2_000 });
  });

  it('says so when there is nothing to show, and when the feed cannot be loaded', async () => {
    mockGetHomeFeed.mockResolvedValueOnce(page('s1', 0, 0, false));
    const first = renderFeed();
    await first.rendered;
    expect(await screen.findByTestId('for-you-empty')).toBeTruthy();
    screen.unmount();

    mockGetHomeFeed.mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR', 'boom'));
    await renderFeed().rendered;
    // The feed tries once more (after a second) before it gives up.
    expect(await screen.findByTestId('for-you-error', {}, { timeout: 5_000 })).toBeTruthy();
    expect(mockGetHomeFeed).toHaveBeenCalledTimes(3);
  });
});
