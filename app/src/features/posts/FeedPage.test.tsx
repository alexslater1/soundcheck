import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, within } from '@testing-library/react-native';
import { StyleSheet } from 'react-native';

import type { Post, PostClip } from '@/api/generated/model';
import { handoffFor } from '@/features/player/handoff';
import { ThemeProvider } from '@/theme/ThemeProvider';
import { useThemePrefs, type FeedStyle } from '@/theme/themePrefs';

import { CARD_INSET, cardBox, FeedPage } from './FeedPage';
import { TICKET_HEIGHT } from './GigTicket';

const mockPush = jest.fn();
const mockHandOver = jest.fn((_key: string) => true);

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
jest.mock('expo-router', () => ({
  useRouter: () => ({ push: mockPush }),
  Link: ({ children }: { children: unknown }) => children,
}));
jest.mock('@/api/generated/engagement/engagement', () => ({
  likePost: jest.fn(),
  unlikePost: jest.fn(),
  hidePost: jest.fn(),
  reportPost: jest.fn(),
}));
jest.mock('@/api/generated/posts/posts', () => ({
  getGetPostQueryKey: (id: string) => [`/api/v1/posts/${id}`],
  deletePost: jest.fn(),
}));
// The player is tested on its own; here it shows the surface and corners it was asked for.
jest.mock('@/features/player/VideoPlayer', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { View } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    VideoPlayer: (props: { surface?: string; rounded?: boolean }) =>
      React.createElement(View, {
        testID: `video-${props.surface ?? 'surfaceView'}-${props.rounded ? 'rounded' : 'square'}`,
      }),
    handOverPlayer: (key: string) => mockHandOver(key),
  };
});

const post = {
  id: 'post-1',
  status: 'PUBLISHED',
  author: { id: 'author-1', handle: 'maya', displayName: 'Maya' },
  caption: 'Front row for the encore',
  artists: [{ id: 'artist-1', name: 'Wolf Alice' }],
  gig: {
    id: 'gig-1',
    title: 'Wolf Alice',
    startsAt: '2026-09-26T19:30:00Z',
    venue: { id: 'venue-1', name: 'The O2', city: 'London' },
  },
  likeCount: 2,
  likedByMe: false,
  commentCount: 1,
  playback: {
    status: 'READY',
    hlsUrl: 'http://localhost:8088/media/m/v1/master.m3u8',
    width: 1080,
    height: 1920,
  },
  // A post from before clips existed in the API (one clip, played under the post's id).
  clips: [],
  clipCount: 1,
  withUsers: [],
} as unknown as Post;

/** The Pixel's feed area in points. */
const PAGE = { width: 412, height: 780 };

async function renderPage(style: FeedStyle) {
  useThemePrefs.setState({ feedStyle: style });
  const client = new QueryClient();
  await render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <FeedPage post={post} width={PAGE.width} height={PAGE.height} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
  return client;
}

describe('FeedPage', () => {
  beforeEach(() => jest.clearAllMocks());

  it('Cards: a rounded card inset from the page, with the gig’s ticket over the clip', async () => {
    await renderPage('cards');

    const card = StyleSheet.flatten(screen.getByTestId('feed-card').props.style);
    expect(card.borderRadius).toBeGreaterThan(0);
    expect(card.width).toBe(PAGE.width - CARD_INSET * 2);
    expect(StyleSheet.flatten(screen.getByTestId('post-post-1').props.style).padding).toBe(
      CARD_INSET,
    );
    // The card rounds the clip, which needs a TextureView on Android.
    expect(screen.getByTestId('video-textureView-square')).toBeTruthy();
    // The date is on the ticket in full: month, day and year.
    expect(screen.getByTestId('gig-ticket')).toBeTruthy();
    expect(screen.getByText('SEP')).toBeTruthy();
    expect(screen.getByTestId('gig-ticket-day').props.children).toBe('26');
    expect(screen.getByText('2026')).toBeTruthy();
    expect(screen.getByText('The O2, London')).toBeTruthy();
    // The ticket says it; the caption block does not say it again.
    expect(screen.queryByTestId('post-gig')).toBeNull();
  });

  it('Full screen: the clip edge to edge with square corners, the ticket still on it', async () => {
    await renderPage('immersive');

    const card = StyleSheet.flatten(screen.getByTestId('feed-card').props.style);
    expect(card.borderRadius).toBeUndefined();
    expect(card.width).toBe(PAGE.width);
    expect(screen.getByTestId('video-surfaceView-square')).toBeTruthy();
    expect(screen.getByTestId('gig-ticket')).toBeTruthy();
  });

  it('Ticket: the gig on a stub above a shorter rounded card', async () => {
    await renderPage('ticket');

    const card = StyleSheet.flatten(screen.getByTestId('feed-card').props.style);
    expect(card.height).toBe(PAGE.height - CARD_INSET * 2 - TICKET_HEIGHT - CARD_INSET);
    // The ticket is on the page, not inside the card.
    expect(screen.getByTestId('gig-ticket')).toBeTruthy();
    expect(within(screen.getByTestId('feed-card')).queryByTestId('gig-ticket')).toBeNull();
  });

  it('opens the post from the caption with the clip handed over and the post already there', async () => {
    const client = await renderPage('cards');

    await fireEvent.press(screen.getByText('Front row for the encore'));

    expect(mockHandOver).toHaveBeenCalledWith('post-1');
    expect(handoffFor('post-1')?.muted).toBe(true);
    expect(client.getQueryData(['/api/v1/posts/post-1'])).toBe(post);
    expect(mockPush).toHaveBeenCalledWith({ pathname: '/post/[id]', params: { id: 'post-1' } });
  });

  it('opens the post from the comment count the same way', async () => {
    await renderPage('immersive');

    await fireEvent.press(screen.getByTestId('comment-button'));

    expect(mockHandOver).toHaveBeenCalledWith('post-1');
    expect(mockPush).toHaveBeenCalledTimes(1);
  });
});

describe('FeedPage with several clips', () => {
  beforeEach(() => jest.clearAllMocks());

  const clip = (id: string): PostClip =>
    ({
      id,
      position: 0,
      owner: post.author,
      status: 'READY',
      playback: post.playback,
    }) as PostClip;

  it('opens the post on the clip in view and hands that clip’s player over', async () => {
    const several = { ...post, clips: [clip('c1'), clip('c2'), clip('c3')], clipCount: 3 };
    useThemePrefs.setState({ feedStyle: 'cards' });
    await render(
      <QueryClientProvider client={new QueryClient()}>
        <ThemeProvider>
          <FeedPage post={several} width={PAGE.width} height={PAGE.height} />
        </ThemeProvider>
      </QueryClientProvider>,
    );
    const width = PAGE.width - CARD_INSET * 2;
    await act(async () => {
      fireEvent(screen.getByTestId('clip-frame'), 'layout', {
        nativeEvent: { layout: { width, height: 600, x: 0, y: 0 } },
      });
    });
    await act(async () => {
      fireEvent.scroll(screen.getByTestId('clip-list'), {
        nativeEvent: {
          contentOffset: { x: width, y: 0 },
          contentSize: { width: width * 3, height: 600 },
          layoutMeasurement: { width, height: 600 },
        },
      });
    });
    expect(screen.getByTestId('clip-counter').props.children).toEqual([2, '/', 3]);

    await fireEvent.press(screen.getByText('Front row for the encore'));

    expect(mockHandOver).toHaveBeenCalledWith('c2');
    expect(handoffFor('post-1')?.clipKey).toBe('c2');
    expect(mockPush).toHaveBeenCalledWith({
      pathname: '/post/[id]',
      params: { id: 'post-1', clip: 'c2' },
    });
  });
});

describe('cardBox', () => {
  it('leaves room for the ticket only in the Ticket style, and only for a post with a gig', () => {
    expect(cardBox('ticket', 400, 800, true).height).toBe(800 - 16 - TICKET_HEIGHT - 8);
    expect(cardBox('ticket', 400, 800, false).height).toBe(800 - 16);
    expect(cardBox('cards', 400, 800, true)).toEqual({
      inset: 8,
      ticket: 0,
      width: 384,
      height: 784,
    });
    expect(cardBox('immersive', 400, 800, true)).toEqual({
      inset: 0,
      ticket: 0,
      width: 400,
      height: 800,
    });
  });
});
