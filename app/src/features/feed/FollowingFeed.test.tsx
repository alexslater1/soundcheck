import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import type { PagePost as FollowingPage, Post } from '@/api/generated/model';
import type { PagerItem } from '@/features/posts/PostPager';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { FollowingFeed } from './FollowingFeed';

const mockGetFollowingFeed = jest.fn<
  Promise<FollowingPage>,
  [{ limit?: number; cursor?: string }]
>();

jest.mock('@/api/generated/feed/feed', () => ({
  getFollowingFeed: (params: { limit?: number; cursor?: string }) => mockGetFollowingFeed(params),
  getGetFollowingFeedQueryKey: () => ['/api/v1/feed/following'],
}));
// The screen gains focus once, when it mounts; `mockFocusAgain` brings it back to the front.
const mockFocusCallbacks: (() => void)[] = [];
function mockFocusAgain() {
  mockFocusCallbacks.forEach((callback) => callback());
}
jest.mock('expo-router', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  return {
    useFocusEffect: (callback: () => void) => {
      React.useEffect(() => {
        mockFocusCallbacks.push(callback);
        callback();
      }, [callback]);
    },
  };
});
jest.mock('@/features/posts/PostPager', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { Text, View } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    PostPager: ({
      items,
      onRefresh,
      refreshing,
    }: {
      items: PagerItem[];
      onRefresh?: () => void;
      refreshing?: boolean;
    }) =>
      React.createElement(
        View,
        { testID: refreshing ? 'pager-refreshing' : 'pager' },
        ...items.map((item) => React.createElement(Text, { key: item.post.id }, item.post.id)),
        React.createElement(Text, { onPress: onRefresh }, 'pull'),
      ),
  };
});

function feedPage(ids: string[], nextCursor: string | null = null): FollowingPage {
  return { items: ids.map((id) => ({ id }) as Post), nextCursor };
}

async function renderFeed() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  await render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <FollowingFeed />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('FollowingFeed', () => {
  beforeEach(() => {
    mockGetFollowingFeed.mockReset();
    mockFocusCallbacks.length = 0;
  });

  it('refreshes from the newest post when pulled, keeping the old posts until then', async () => {
    let answer: (page: FollowingPage) => void = () => undefined;
    mockGetFollowingFeed
      .mockResolvedValueOnce(feedPage(['a', 'b'], 'c1'))
      .mockReturnValueOnce(new Promise((resolve) => (answer = resolve)));
    await renderFeed();
    await screen.findByText('a');

    await fireEvent.press(screen.getByText('pull'));

    await waitFor(() => expect(screen.getByTestId('pager-refreshing')).toBeTruthy());
    expect(screen.getByText('a')).toBeTruthy();
    expect(mockGetFollowingFeed).toHaveBeenLastCalledWith({ limit: 10, cursor: undefined });

    answer(feedPage(['new', 'a', 'b']));
    expect(await screen.findByText('new')).toBeTruthy();
    await waitFor(() => expect(screen.getByTestId('pager')).toBeTruthy(), { timeout: 2_000 });
    expect(mockGetFollowingFeed).toHaveBeenCalledTimes(2);
  });

  it('does not reload behind the viewer’s back when the screen comes to the front again', async () => {
    mockGetFollowingFeed.mockResolvedValue(feedPage(['a', 'b']));
    await renderFeed();
    await screen.findByText('a');

    await act(async () => {
      mockFocusAgain();
      await new Promise((resolve) => setTimeout(resolve, 50));
    });
    expect(mockGetFollowingFeed).toHaveBeenCalledTimes(1);
  });

  it('looks again when it comes to the front empty (after following someone), and offers a retry', async () => {
    mockGetFollowingFeed
      .mockResolvedValueOnce(feedPage([]))
      .mockResolvedValueOnce(feedPage(['first']));
    await renderFeed();
    expect(await screen.findByTestId('feed-empty')).toBeTruthy();
    expect(screen.getByText('Try again')).toBeTruthy();

    await act(async () => mockFocusAgain());

    expect(await screen.findByText('first')).toBeTruthy();
    expect(mockGetFollowingFeed).toHaveBeenCalledTimes(2);
  });
});
