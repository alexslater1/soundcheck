import { render, screen } from '@testing-library/react-native';

import type { Post } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { PostMeta } from './PostMeta';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
jest.mock('expo-router', () => ({ Link: ({ children }: { children: unknown }) => children }));
jest.mock('@/features/telemetry/TelemetryScope', () => ({ useTrack: () => jest.fn() }));

const post = (withUsers: Post['withUsers']): Post =>
  ({
    id: 'p1',
    author: { id: 'u1', handle: 'maya', displayName: 'Maya' },
    artists: [],
    withUsers,
  }) as unknown as Post;

describe('PostMeta: the people the author went with', () => {
  it('says "with" and names each tagged person as a link to their profile', async () => {
    await render(
      <ThemeProvider>
        <PostMeta
          post={post([
            { id: 'u2', handle: 'sam', displayName: 'Sam' },
            { id: 'u3', handle: 'jo', displayName: 'Jo' },
          ] as Post['withUsers'])}
        />
      </ThemeProvider>,
    );
    expect(screen.getByTestId('post-with')).toBeTruthy();
    expect(screen.getByTestId('post-with-sam').props.accessibilityRole).toBe('link');
    expect(screen.getByText('@sam,')).toBeTruthy();
    expect(screen.getByText('@jo')).toBeTruthy();
  });

  it('has no "with" line when nobody is tagged', async () => {
    await render(
      <ThemeProvider>
        <PostMeta post={post([])} />
      </ThemeProvider>,
    );
    expect(screen.queryByTestId('post-with')).toBeNull();
  });
});
