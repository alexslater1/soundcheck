import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { Text } from 'react-native';

import { telemetry } from '@/features/telemetry/telemetry';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { LikeButton } from './LikeButton';
import { useLike, useLikes } from './useLike';

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
  beforeEach(() => {
    jest.clearAllMocks();
    // The like state is shared by post (useLike): each test starts from what the server says.
    useLikes.setState({ entries: {} });
  });

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
    // Nothing is reported until the server has the like.
    expect(telemetry.track).not.toHaveBeenCalled();
    answer({ likedByMe: true, likeCount: 7 });
    await waitFor(() => expect(screen.getByTestId('like-count').props.children).toBe(7));
    expect(telemetry.track).toHaveBeenCalledTimes(1);
    expect(telemetry.track).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'LIKE', postId: 'post-1', surface: 'POST' }),
    );
  });

  it('puts the heart back when the request fails', async () => {
    mockLike.mockRejectedValue(new Error('offline'));
    await renderButton();

    await fireEvent.press(screen.getByLabelText('Like'));

    await waitFor(() => expect(screen.getByLabelText('Like')).toBeTruthy());
    // A like that failed is not a signal.
    expect(telemetry.track).not.toHaveBeenCalled();
    expect(screen.getByTestId('like-count').props.children).toBe(4);
  });

  it('unlikes a liked post', async () => {
    mockUnlike.mockResolvedValue({ likedByMe: false, likeCount: 3 });
    await renderButton({ likedByMe: true, likeCount: 4 });

    await fireEvent.press(screen.getByLabelText('Unlike'));

    await waitFor(() => expect(screen.getByTestId('like-count').props.children).toBe(3));
    expect(mockUnlike).toHaveBeenCalledWith('post-1');
  });

  it('shows a like made in one place everywhere the post is shown', async () => {
    mockLike.mockResolvedValue({ likedByMe: true, likeCount: 5 });
    await render(
      <ThemeProvider>
        <LikeButton postId="post-1" initial={{ likedByMe: false, likeCount: 4 }} />
        <LikeButton postId="post-1" initial={{ likedByMe: false, likeCount: 4 }} />
      </ThemeProvider>,
    );

    await fireEvent.press(screen.getAllByLabelText('Like')[0]!);

    await waitFor(() => expect(screen.getAllByLabelText('Unlike')).toHaveLength(2));
    expect(mockLike).toHaveBeenCalledTimes(1);
  });

  it('lets newer numbers from the server replace what was guessed', async () => {
    mockLike.mockResolvedValue({ likedByMe: true, likeCount: 5 });
    const view = await renderButton();
    await fireEvent.press(screen.getByLabelText('Like'));
    await waitFor(() => expect(screen.getByTestId('like-count').props.children).toBe(5));

    // The post was fetched again: someone unliked meanwhile; the server's answer wins.
    await view.rerender(
      <ThemeProvider>
        <LikeButton postId="post-1" initial={{ likedByMe: false, likeCount: 9 }} />
      </ThemeProvider>,
    );
    expect(screen.getByTestId('like-count').props.children).toBe(9);
    expect(screen.getByLabelText('Like')).toBeTruthy();
  });
});

/** Stands in for a clip: a double tap calls `like()`. */
function DoubleTapTarget({ initial }: { initial: { likedByMe: boolean; likeCount: number } }) {
  const { like } = useLike('post-1', initial);
  return <Text onPress={() => void like()}>double tap</Text>;
}

describe('useLike().like (double tap)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useLikes.setState({ entries: {} });
  });

  it('likes a post that is not liked yet, and the heart shows it', async () => {
    mockLike.mockResolvedValue({ likedByMe: true, likeCount: 5 });
    const initial = { likedByMe: false, likeCount: 4 };
    await render(
      <ThemeProvider>
        <DoubleTapTarget initial={initial} />
        <LikeButton postId="post-1" initial={initial} />
      </ThemeProvider>,
    );

    await fireEvent.press(screen.getByText('double tap'));

    await waitFor(() => expect(screen.getByLabelText('Unlike')).toBeTruthy());
    expect(mockLike).toHaveBeenCalledWith('post-1');
  });

  it('never unlikes: a double tap on a liked post leaves it liked', async () => {
    const initial = { likedByMe: true, likeCount: 4 };
    await render(
      <ThemeProvider>
        <DoubleTapTarget initial={initial} />
        <LikeButton postId="post-1" initial={initial} />
      </ThemeProvider>,
    );

    await fireEvent.press(screen.getByText('double tap'));
    await fireEvent.press(screen.getByText('double tap'));

    expect(screen.getByLabelText('Unlike')).toBeTruthy();
    expect(screen.getByTestId('like-count').props.children).toBe(4);
    expect(mockLike).not.toHaveBeenCalled();
    expect(mockUnlike).not.toHaveBeenCalled();
  });
});
