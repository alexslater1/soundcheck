import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { LikeButton } from './LikeButton';

const mockLike = jest.fn();
const mockUnlike = jest.fn();

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
jest.mock('@/api/generated/engagement/engagement', () => ({
  likePost: (id: string) => mockLike(id),
  unlikePost: (id: string) => mockUnlike(id),
}));

function renderButton(initial = { likedByMe: false, likeCount: 4 }) {
  return render(
    <ThemeProvider>
      <LikeButton postId="post-1" initial={initial} />
    </ThemeProvider>,
  );
}

describe('LikeButton', () => {
  beforeEach(() => jest.clearAllMocks());

  it('shows the like at once and then the count the server reports', async () => {
    let answer: (value: unknown) => void = () => undefined;
    mockLike.mockReturnValue(new Promise((resolve) => (answer = resolve)));
    await renderButton();

    await fireEvent.press(screen.getByLabelText('Like'));

    // Before the server has answered.
    expect(screen.getByTestId('like-count').props.children).toBe(5);
    expect(screen.getByLabelText('Unlike')).toBeTruthy();
    expect(mockLike).toHaveBeenCalledWith('post-1');

    // Someone else liked it in the meantime: the server's count wins.
    answer({ likedByMe: true, likeCount: 7 });
    await waitFor(() => expect(screen.getByTestId('like-count').props.children).toBe(7));
  });

  it('puts the heart back when the request fails', async () => {
    mockLike.mockRejectedValue(new Error('offline'));
    await renderButton();

    await fireEvent.press(screen.getByLabelText('Like'));

    await waitFor(() => expect(screen.getByLabelText('Like')).toBeTruthy());
    expect(screen.getByTestId('like-count').props.children).toBe(4);
  });

  it('unlikes a liked post', async () => {
    mockUnlike.mockResolvedValue({ likedByMe: false, likeCount: 3 });
    await renderButton({ likedByMe: true, likeCount: 4 });

    await fireEvent.press(screen.getByLabelText('Unlike'));

    await waitFor(() => expect(screen.getByTestId('like-count').props.children).toBe(3));
    expect(mockUnlike).toHaveBeenCalledWith('post-1');
  });
});
