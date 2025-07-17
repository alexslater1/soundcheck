import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react-native';

import type { PagePost, Post } from '@/api/generated/model';
import type { PagerItem } from '@/features/posts/PostPager';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { TaggedPostsFeed, type TaggedKind } from './TaggedPostsFeed';

type Params = { limit?: number; cursor?: string };
const mockArtistPosts = jest.fn<Promise<PagePost>, [string, Params]>();
const mockVenuePosts = jest.fn<Promise<PagePost>, [string, Params]>();

jest.mock('@/api/generated/posts/posts', () => ({
  listArtistPosts: (id: string, params: Params) => mockArtistPosts(id, params),
  listVenuePosts: (id: string, params: Params) => mockVenuePosts(id, params),
  getListArtistPostsQueryKey: (id: string) => [`/api/v1/artists/${id}/posts`],
  getListVenuePostsQueryKey: (id: string) => [`/api/v1/venues/${id}/posts`],
}));
const mockPush = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush }) }));
const mockSurfaces: string[] = [];
jest.mock('@/features/posts/PostPager', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { Text, View } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    PostPager: ({ items, surface }: { items: PagerItem[]; surface: string }) => {
      mockSurfaces.push(surface);
      return React.createElement(
        View,
        { testID: 'pager' },
        ...items.map((item) => React.createElement(Text, { key: item.post.id }, item.post.id)),
      );
    },
  };
});

const page = (ids: string[], nextCursor: string | null = null): PagePost => ({
  items: ids.map((id) => ({ id }) as Post),
  nextCursor,
});

async function renderFeed(kind: TaggedKind) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  await render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <TaggedPostsFeed kind={kind} id="x1" name="Oasis" />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('TaggedPostsFeed', () => {
  beforeEach(() => {
    mockArtistPosts.mockReset();
    mockVenuePosts.mockReset();
    mockPush.mockClear();
  });

  it("plays an artist's clips in the feed pager, as the SEARCH surface", async () => {
    mockArtistPosts.mockResolvedValue(page(['p1', 'p2']));
    await renderFeed('artist');
    expect(await screen.findByText('p2')).toBeTruthy();
    expect(mockArtistPosts).toHaveBeenCalledWith('x1', { limit: 10, cursor: undefined });
    expect(mockSurfaces.at(-1)).toBe('SEARCH');
  });

  it('skips past an empty page the server could not fill to the next one', async () => {
    mockVenuePosts.mockResolvedValueOnce(page([], 'c1')).mockResolvedValueOnce(page(['p3']));
    await renderFeed('venue');
    expect(await screen.findByText('p3')).toBeTruthy();
    expect(mockVenuePosts).toHaveBeenLastCalledWith('x1', { limit: 10, cursor: 'c1' });
    expect(mockArtistPosts).not.toHaveBeenCalled();
  });

  it('says when there are no clips yet and links to the page', async () => {
    mockArtistPosts.mockResolvedValue(page([]));
    await renderFeed('artist');
    expect(await screen.findByText('No clips of Oasis yet')).toBeTruthy();
    await fireEvent.press(screen.getByText('Open the artist page'));
    expect(mockPush).toHaveBeenCalledWith({ pathname: '/artist/[id]', params: { id: 'x1' } });
  });

  it('offers a retry when the clips could not be loaded', async () => {
    mockArtistPosts.mockRejectedValue(new Error('offline'));
    await renderFeed('artist');
    expect(await screen.findByTestId('tagged-error')).toBeTruthy();
  });
});
