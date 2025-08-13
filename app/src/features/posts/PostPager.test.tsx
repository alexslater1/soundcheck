import { act, fireEvent, render, screen } from '@testing-library/react-native';

import type { Post } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { PostPager, type PagerItem } from './PostPager';
import { REFRESH_HEADER_HEIGHT } from './RefreshHeader';

const mockMounts: string[] = [];

// A page shows what its player slot allows, instead of a clip.
jest.mock('./FeedPage', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  const { usePlayerSlot } = jest.requireActual<typeof import('@/features/player/playerManager')>(
    '@/features/player/playerManager',
  );
  return {
    FeedPage: ({ post }: { post: Post }) => {
      const { live, active } = usePlayerSlot(post.id);
      React.useEffect(() => {
        mockMounts.push(post.id);
      }, [post.id]);
      return React.createElement(
        Text,
        { testID: `page-${post.id}` },
        active ? 'playing' : live ? 'live' : 'poster',
      );
    },
  };
});
jest.mock('@react-navigation/native', () => ({ useIsFocused: () => true }));

const items: PagerItem[] = ['a', 'b', 'c'].map((id, position) => ({
  post: { id } as Post,
  position,
}));

async function renderPager(props: Partial<React.ComponentProps<typeof PostPager>> = {}) {
  const view = await render(
    <ThemeProvider>
      <PostPager items={items} surface="FOLLOWING" {...props} />
    </ThemeProvider>,
  );
  await fireEvent(screen.getByTestId('post-pager'), 'layout', {
    nativeEvent: { layout: { x: 0, y: 0, width: 400, height: 700 } },
  });
  return view;
}

const scrolledTo = (y: number) => ({ nativeEvent: { contentOffset: { x: 0, y } } });

describe('PostPager', () => {
  it('starts with the refresh header out of sight and snaps to it or to a page', async () => {
    await renderPager({ onRefresh: jest.fn() });

    const list = screen.getByTestId('post-pager-list');
    expect(screen.getByTestId('refresh-header')).toBeTruthy();
    expect(list.props.contentOffset).toEqual({ x: 0, y: REFRESH_HEADER_HEIGHT });
    expect(list.props.snapToOffsets).toEqual([0, 96, 796, 1496]);
    expect(list.props.pagingEnabled).toBeUndefined();
  });

  it('refreshes when the feed comes to rest on its header, and not elsewhere', async () => {
    const onRefresh = jest.fn();
    await renderPager({ onRefresh });
    const list = screen.getByTestId('post-pager-list');

    await fireEvent(list, 'momentumScrollEnd', scrolledTo(REFRESH_HEADER_HEIGHT + 700));
    await fireEvent(list, 'scrollEndDrag', scrolledTo(40));
    expect(onRefresh).not.toHaveBeenCalled();

    await fireEvent(list, 'momentumScrollEnd', scrolledTo(0));
    expect(onRefresh).toHaveBeenCalledTimes(1);
  });

  it('does not refresh again while a refresh is running, and dances the bars meanwhile', async () => {
    const onRefresh = jest.fn();
    await renderPager({ onRefresh, refreshing: true });

    await fireEvent(screen.getByTestId('post-pager-list'), 'momentumScrollEnd', scrolledTo(0));

    expect(onRefresh).not.toHaveBeenCalled();
    expect(screen.getByTestId('equalizer-animating')).toBeTruthy();
  });

  it('draws the pages afresh when its size settles, as under a page header', async () => {
    await renderPager();
    mockMounts.length = 0;
    await fireEvent(screen.getByTestId('post-pager'), 'layout', {
      nativeEvent: { layout: { x: 0, y: 0, width: 400, height: 640 } },
    });
    expect(mockMounts).toContain('a');
  });

  it('pages plainly without a refresh', async () => {
    await renderPager();
    const list = screen.getByTestId('post-pager-list');
    expect(screen.queryByTestId('refresh-header')).toBeNull();
    expect(list.props.pagingEnabled).toBe(true);
  });

  it('holds no players while hidden and plays where it was when shown again', async () => {
    const view = await renderPager();
    const list = screen.getByTestId('post-pager-list');
    await act(async () => {
      list.props.viewabilityConfigCallbackPairs[0].onViewableItemsChanged({
        viewableItems: [{ key: 'b', item: items[1], isViewable: true, index: 1 }],
        changed: [],
      });
    });
    expect(screen.getByTestId('page-b').props.children).toBe('playing');

    await view.rerender(
      <ThemeProvider>
        <PostPager items={items} surface="FOLLOWING" visible={false} />
      </ThemeProvider>,
    );
    // (The list renders the first two pages.)
    expect(['a', 'b'].map((id) => screen.getByTestId(`page-${id}`).props.children)).toEqual([
      'poster',
      'poster',
    ]);

    await view.rerender(
      <ThemeProvider>
        <PostPager items={items} surface="FOLLOWING" visible />
      </ThemeProvider>,
    );
    expect(screen.getByTestId('page-b').props.children).toBe('playing');
  });
});
