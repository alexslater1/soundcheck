import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import type { Post } from '@/api/generated/model';
import { useAuthStore } from '@/features/auth/store';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { EditPostScreen } from './EditPostScreen';

const mockBack = jest.fn();
const mockEdit = jest.fn(async (_id: string, _body: unknown) => ({}));
let mockQuery: { isPending: boolean; isError: boolean; data?: Post; refetch: () => void };

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
jest.mock('expo-image', () => ({ Image: () => null }));
jest.mock('expo-router', () => ({ useRouter: () => ({ back: mockBack, push: jest.fn() }) }));
jest.mock('@tanstack/react-query', () => ({
  ...jest.requireActual('@tanstack/react-query'),
  useQueryClient: () => ({ invalidateQueries: jest.fn(async () => undefined) }),
}));
jest.mock('@/api/generated/posts/posts', () => ({
  useGetPost: () => mockQuery,
  editPost: (id: string, body: unknown) => mockEdit(id, body),
}));
jest.mock('@/api/generated/social/social', () => ({
  useListFollowing: () => ({
    isPending: false,
    data: { items: [{ id: 'mate', handle: 'mate', displayName: 'Mate' }] },
  }),
}));

const me = { id: 'me', handle: 'me', displayName: 'Me' };
const playback = { status: 'READY', thumbUrl: null } as never;
const post = {
  id: 'p1',
  author: me,
  caption: 'Hello',
  gig: { id: 'g1', headliner: { id: 'keo', name: 'Keo' }, support: [] },
  clips: [
    { id: 'c1', position: 0, act: null, owner: me, status: 'READY', playback },
    { id: 'c2', position: 1, act: null, owner: me, status: 'READY', playback },
  ],
  withUsers: [],
} as unknown as Post;

function renderScreen() {
  return render(
    <ThemeProvider>
      <EditPostScreen id="p1" />
    </ThemeProvider>,
  );
}

describe('EditPostScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useAuthStore.setState({ me: { id: 'me' } as never });
  });

  it('shows a skeleton while loading and refuses someone else', async () => {
    mockQuery = { isPending: true, isError: false, refetch: jest.fn() };
    const view = await renderScreen();
    expect(screen.getByTestId('edit-loading')).toBeTruthy();
    await view.unmount();

    mockQuery = {
      isPending: false,
      isError: false,
      data: { ...post, author: { ...me, id: 'other' } },
      refetch: jest.fn(),
    };
    await renderScreen();
    expect(screen.getByTestId('edit-not-yours')).toBeTruthy();
  });

  it('saves only what changed and goes back', async () => {
    mockQuery = { isPending: false, isError: false, data: post, refetch: jest.fn() };
    await renderScreen();

    await fireEvent.press((await screen.findAllByLabelText('Move down'))[0]!);
    await fireEvent.press(screen.getByText('@mate'));
    await fireEvent.press(screen.getByTestId('edit-save'));

    await waitFor(() => expect(mockBack).toHaveBeenCalled());
    expect(mockEdit).toHaveBeenCalledWith('p1', {
      clips: [
        { id: 'c2', artistId: null, song: null },
        { id: 'c1', artistId: null, song: null },
      ],
      taggedUserIds: ['mate'],
    });
  });
});
