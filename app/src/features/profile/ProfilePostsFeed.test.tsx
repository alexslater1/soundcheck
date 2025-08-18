import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen } from '@testing-library/react-native';

import type { PagePost, Post } from '@/api/generated/model';
import type { PagerItem } from '@/features/posts/PostPager';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { PROFILE_PAGE, ProfilePostsFeed } from './ProfilePostsFeed';

type Params = { limit?: number; cursor?: string };
const mockUserPosts = jest.fn<Promise<PagePost>, [string, Params]>();
const mockMyPosts = jest.fn<Promise<PagePost>, [Params]>();

jest.mock('@/api/generated/posts/posts', () => ({
  listUserPosts: (id: string, params: Params) => mockUserPosts(id, params),
  listMyPosts: (params: Params) => mockMyPosts(params),
  getListUserPostsQueryKey: (id: string) => [`/api/v1/users/${id}/posts`],
  getListMyPostsQueryKey: () => ['/api/v1/me/posts'],
}));
const mockPager: { items: PagerItem[]; initialIndex?: number; onEndReached?: () => void }[] = [];
jest.mock('@/features/posts/PostPager', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { Text, View } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    PostPager: (props: {
      items: PagerItem[];
      surface: string;
      initialIndex?: number;
      onEndReached?: () => void;
    }) => {
      mockPager.push(props);
      return React.createElement(
        View,
        { testID: `pager-${props.surface}` },
        ...props.items.map((item) =>
          React.createElement(Text, { key: item.post.id }, item.post.id),
        ),
      );
    },
  };
});

const page = (ids: string[], nextCursor: string | null = null): PagePost => ({
  items: ids.map((id) => ({ id }) as Post),
  nextCursor,
});

async function renderFeed(mine: boolean, start?: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  await render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <ProfilePostsFeed userId="u1" mine={mine} startPostId={start} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('ProfilePostsFeed', () => {
  beforeEach(() => {
    mockUserPosts.mockReset();
    mockMyPosts.mockReset();
    mockPager.length = 0;
  });

  it("opens a profile's posts at the one tapped, and loads the next page at the end", async () => {
    mockUserPosts
      .mockResolvedValueOnce(page(['p1', 'p2', 'p3'], 'c1'))
      .mockResolvedValueOnce(page(['p4']));
    await renderFeed(false, 'p2');

    expect(await screen.findByText('p3')).toBeTruthy();
    expect(screen.getByTestId('pager-PROFILE')).toBeTruthy();
    // The same list as the grid, as many a page, so the tapped post is on the first.
    expect(mockUserPosts).toHaveBeenCalledWith('u1', { limit: PROFILE_PAGE, cursor: undefined });
    expect(mockPager.at(-1)?.initialIndex).toBe(1);

    await act(async () => mockPager.at(-1)?.onEndReached?.());
    expect(await screen.findByText('p4')).toBeTruthy();
    expect(mockUserPosts).toHaveBeenLastCalledWith('u1', { limit: PROFILE_PAGE, cursor: 'c1' });
  });

  it('reads your own list on Me, and opens at the first post without a start', async () => {
    mockMyPosts.mockResolvedValue(page(['m1', 'm2']));
    await renderFeed(true);
    expect(await screen.findByText('m2')).toBeTruthy();
    expect(mockUserPosts).not.toHaveBeenCalled();
    expect(mockPager.at(-1)?.initialIndex).toBe(0);
  });

  it('offers a retry when the posts could not be loaded', async () => {
    mockUserPosts.mockRejectedValue(new Error('private'));
    await renderFeed(false, 'p1');
    expect(await screen.findByTestId('profile-posts-error')).toBeTruthy();
  });
});
