import { render, screen } from '@testing-library/react-native';

import type { Post } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { PostGrid } from './PostGrid';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
const mockHrefs: unknown[] = [];
jest.mock('expo-router', () => ({
  Link: ({ children, href }: { children: unknown; href: unknown }) => {
    mockHrefs.push(href);
    return children;
  },
}));

const post = (id: string, clipCount: number): Post =>
  ({
    id,
    status: 'PUBLISHED',
    clipCount,
    playback: { status: 'READY', thumbUrl: `http://media/${id}.webp` },
  }) as unknown as Post;

describe('PostGrid', () => {
  it('shows one tile per post, with the number of clips only when there are several', async () => {
    await render(
      <ThemeProvider>
        <PostGrid posts={[post('p1', 1), post('p2', 4)]} />
      </ThemeProvider>,
    );
    expect(screen.getAllByTestId('post-tile')).toHaveLength(2);
    const badges = screen.getAllByTestId('post-tile-clips');
    expect(badges).toHaveLength(1);
    expect(badges[0]!.props.accessibilityLabel).toBe('4 clips');
  });

  it('with an act, shows and opens that act’s first clip', async () => {
    mockHrefs.length = 0;
    const night = {
      ...post('p3', 2),
      clips: [
        { id: 'c1', act: { id: 'keo' }, playback: { thumbUrl: 'http://media/c1.webp' } },
        { id: 'c2', act: { id: 'tooth' }, playback: { thumbUrl: 'http://media/c2.webp' } },
      ],
    } as unknown as Post;
    await render(
      <ThemeProvider>
        <PostGrid posts={[night]} actId="tooth" />
      </ThemeProvider>,
    );
    expect(mockHrefs.at(-1)).toEqual({ pathname: '/post/[id]', params: { id: 'p3', clip: 'c2' } });
  });
});
