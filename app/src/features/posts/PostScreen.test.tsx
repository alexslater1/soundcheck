import { fireEvent, render, screen } from '@testing-library/react-native';
import { ScrollView, StyleSheet, TextInput } from 'react-native';

import type { Post } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { PostScreen } from './PostScreen';

const post = {
  id: 'post-1',
  status: 'PUBLISHED',
  author: { id: 'author-1', handle: 'maya', displayName: 'Maya' },
  caption: 'Front row',
  artists: [],
  gig: null,
  likeCount: 2,
  likedByMe: false,
  commentCount: 1,
  playback: {
    status: 'READY',
    hlsUrl: 'http://localhost:8088/media/m/v1/master.m3u8',
    width: 1080,
    height: 1920,
  },
} as unknown as Post;

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
jest.mock('expo-router', () => ({
  useRouter: () => ({ push: jest.fn(), back: jest.fn(), canGoBack: () => true }),
  Link: ({ children }: { children: unknown }) => children,
}));
jest.mock('@/api/generated/posts/posts', () => ({
  useGetPost: () => ({ isPending: false, data: post }),
  getGetPostQueryKey: (id: string) => [`/api/v1/posts/${id}`],
  deletePost: jest.fn(),
}));
jest.mock('@/api/generated/engagement/engagement', () => ({
  useListComments: () => ({ isPending: false, data: { items: [] } }),
  getListCommentsQueryKey: (id: string) => [`/api/v1/posts/${id}/comments`],
  addComment: jest.fn(),
  deleteComment: jest.fn(),
  likePost: jest.fn(),
  unlikePost: jest.fn(),
  hidePost: jest.fn(),
  reportPost: jest.fn(),
}));
// The player is tested on its own; here it shows which surface the post page asked for.
jest.mock('@/features/player/VideoPlayer', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { View } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    VideoPlayer: (props: { surface?: string }) =>
      React.createElement(View, { testID: `video-${props.surface ?? 'surfaceView'}` }),
    handOverPlayer: jest.fn(),
  };
});
jest.mock('@tanstack/react-query', () => ({
  ...jest.requireActual<typeof import('@tanstack/react-query')>('@tanstack/react-query'),
  useQueryClient: () => ({ invalidateQueries: jest.fn() }),
}));

describe('PostScreen', () => {
  it('scrolls the clip, details and comments as one page, with no fixed band above them', async () => {
    await render(
      <ThemeProvider>
        <PostScreen id="post-1" />
      </ThemeProvider>,
    );

    const scroll = screen.getByTestId('post-scroll');
    // The padding above the content belongs to the scrolling content, not to the column around it.
    expect(StyleSheet.flatten(scroll.props.contentContainerStyle).paddingTop).toBeGreaterThan(0);
    // RNTL 13's `parent` can be a composite element (the ScrollView mock): the column is the nearest
    // host element above.
    let column = scroll.parent;
    while (column && typeof column.type !== 'string') {
      column = column.parent;
    }
    expect(StyleSheet.flatten(column?.props.style).paddingTop).toBe(0);
    // A TextureView keeps the clip's rounded corners while the page scrolls.
    expect(screen.getByTestId('video-textureView')).toBeTruthy();
  });

  it('takes the comment button to the comment box instead of opening the page again', async () => {
    // The test renderer's ScrollView and TextInput are mocks whose methods are recorded.
    const focus = jest.spyOn(TextInput.prototype, 'focus');
    const scrollTo = jest.spyOn(ScrollView.prototype, 'scrollTo');
    await render(
      <ThemeProvider>
        <PostScreen id="post-1" />
      </ThemeProvider>,
    );
    await fireEvent.press(screen.getByTestId('comment-button'));

    expect(scrollTo).toHaveBeenCalledWith(expect.objectContaining({ animated: true }));
    expect(focus).toHaveBeenCalled();
  });
});
