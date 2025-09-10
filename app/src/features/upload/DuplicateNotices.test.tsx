import { fireEvent, render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { DuplicateNotices } from './DuplicateNotices';
import { useDuplicateNotices } from './duplicates';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
const mockHrefs: unknown[] = [];
jest.mock('expo-router', () => ({
  Link: ({ href, children }: { href: unknown; children: unknown }) => {
    mockHrefs.push(href);
    return children;
  },
}));

const show = (ownerId: string) =>
  render(
    <ThemeProvider>
      <DuplicateNotices ownerId={ownerId} />
    </ThemeProvider>,
  );

describe('DuplicateNotices', () => {
  beforeEach(() => {
    mockHrefs.length = 0;
    useDuplicateNotices.setState({ notices: [] });
  });

  it('shows nothing when no clip was refused', async () => {
    await show('user-1');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('says the clip is posted already and links to that post; OK dismisses it', async () => {
    useDuplicateNotices.getState().add({
      localId: 'l1',
      ownerId: 'user-1',
      fileName: 'encore.mp4',
      postId: 'post-7',
    });
    useDuplicateNotices.getState().add({
      localId: 'l2',
      ownerId: 'someone-else',
      fileName: null,
      postId: 'post-8',
    });
    await show('user-1');

    expect(screen.getByText('You’ve posted this clip already (encore.mp4).')).toBeTruthy();
    expect(screen.queryByTestId('duplicate-l2')).toBeNull();
    expect(mockHrefs).toEqual([{ pathname: '/post/[id]', params: { id: 'post-7' } }]);

    await fireEvent.press(screen.getByTestId('duplicate-l1-dismiss'));
    expect(useDuplicateNotices.getState().notices.map((n) => n.localId)).toEqual(['l2']);
  });
});
