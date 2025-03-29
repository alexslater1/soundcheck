import { act, render, screen, waitFor } from '@testing-library/react-native';

import { useAuthStore } from '@/features/auth/store';
import { useUploadQueue } from '@/features/upload/uploadQueue';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { MeScreen } from './MeScreen';

const mockRefetch = jest.fn(async () => undefined);
const mockPosts = jest.fn();

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
jest.mock('@/api/generated/posts/posts', () => ({
  useListMyPosts: (...args: unknown[]) => mockPosts(...args),
}));
jest.mock('expo-router', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  return {
    Link: ({ children }: { children: React.ReactNode }) =>
      React.createElement(React.Fragment, null, children),
    // Focus happens once, on mount.
    useFocusEffect: (effect: () => void) => React.useEffect(effect, [effect]),
  };
});

const me = {
  id: 'user-1',
  handle: 'anna',
  displayName: 'Anna',
  genres: [],
  counts: { posts: 0, followers: 0, following: 0 },
};
const video = {
  uri: 'blob:clip',
  fileName: 'clip.mp4',
  contentType: 'video/mp4',
  bytes: 1000,
  durationMs: 5000,
  width: null,
  height: null,
};

describe('MeScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useUploadQueue.setState({ entries: [] });
    useAuthStore.setState({ me: me as never, reloadMe: jest.fn(async () => undefined) });
    mockPosts.mockReturnValue({ isPending: false, data: { items: [] }, refetch: mockRefetch });
  });

  it('says a clip is on its way while its post does not exist yet, and refetches the grid when it does', async () => {
    const id = useUploadQueue.getState().enqueue('user-1', video, { platform: 'web' });
    useUploadQueue.getState().patch(id, { draft: { caption: '', gigId: null, artistIds: [] } });
    await render(
      <ThemeProvider>
        <MeScreen />
      </ThemeProvider>,
    );

    expect(screen.getByTestId('me-uploading')).toBeTruthy();
    expect(screen.queryByTestId('me-no-posts')).toBeNull();
    const fetchesBefore = mockRefetch.mock.calls.length;

    // The upload completes in the background and the post is created.
    await act(async () => useUploadQueue.getState().patch(id, { postId: 'post-1' }));

    await waitFor(() => expect(mockRefetch.mock.calls.length).toBeGreaterThan(fetchesBefore));
    expect(screen.queryByTestId('me-uploading')).toBeNull();
  });

  it('shows the empty state when there is nothing posted and nothing on its way', async () => {
    await render(
      <ThemeProvider>
        <MeScreen />
      </ThemeProvider>,
    );
    expect(screen.getByTestId('me-no-posts')).toBeTruthy();
  });
});
