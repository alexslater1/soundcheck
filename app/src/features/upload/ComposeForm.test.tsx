import { fireEvent, render, screen } from '@testing-library/react-native';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { ComposeForm } from './ComposeForm';

const person = (id: string, handle: string) => ({ id, handle, displayName: handle.toUpperCase() });

const mockFollowing = jest.fn();
jest.mock('@/api/generated/social/social', () => ({
  useListFollowing: (...args: unknown[]) => mockFollowing(...args),
}));
jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

async function renderForm(props: Partial<React.ComponentProps<typeof ComposeForm>> = {}) {
  const onPost = jest.fn();
  const view = await render(
    <ThemeProvider>
      <ComposeForm meId="me" clips={2} hint={null} onPost={onPost} {...props} />
    </ThemeProvider>,
  );
  return { onPost, view };
}

describe('ComposeForm', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockFollowing.mockReturnValue({
      isPending: false,
      data: { items: [person('u1', 'sam'), person('u2', 'alex'), person('u3', 'jo')] },
    });
  });

  it('posts the caption and the people the user was with, picked from who they follow', async () => {
    const { onPost } = await renderForm();
    expect(mockFollowing).toHaveBeenCalledWith('me', { limit: 50 });

    await fireEvent.changeText(screen.getByLabelText('Caption'), 'What a night');
    await fireEvent.press(screen.getByText('@sam'));
    await fireEvent.press(screen.getByText('@jo'));
    await fireEvent.press(screen.getByText('@jo')); // untagged again
    await fireEvent.press(screen.getByText('Post 2 clips'));

    expect(onPost).toHaveBeenCalledWith({ caption: 'What a night', taggedUserIds: ['u1'] });
  });

  it('narrows the people by name or handle', async () => {
    await renderForm();
    await fireEvent.changeText(screen.getByLabelText('Find someone to tag'), 'AL');
    expect(screen.getByText('@alex')).toBeTruthy();
    expect(screen.queryByText('@sam')).toBeNull();
  });

  it('keeps Post disabled with the reason while something is missing', async () => {
    const { onPost } = await renderForm({ hint: 'Add at least one clip to post.' });
    expect(screen.getByTestId('post-hint')).toHaveTextContent('Add at least one clip to post.');
    expect(screen.getByTestId('post-button')).toBeDisabled();
    await fireEvent.press(screen.getByTestId('post-button'));
    expect(onPost).not.toHaveBeenCalled();
  });

  it('asks nothing but Add when the clips join an existing post', async () => {
    const { onPost } = await renderForm({ joining: true, clips: 1 });
    expect(screen.queryByLabelText('Caption')).toBeNull();
    expect(screen.queryByTestId('people-tagger')).toBeNull();
    await fireEvent.press(screen.getByText('Add clip'));
    expect(onPost).toHaveBeenCalledWith({ caption: '', taggedUserIds: [] });
  });

  it('says how to tag people when the user follows nobody, and retries a failed list', async () => {
    mockFollowing.mockReturnValue({ isPending: false, data: { items: [] } });
    const { view } = await renderForm();
    expect(screen.getByTestId('people-empty')).toBeTruthy();
    await view.unmount();

    const refetch = jest.fn();
    mockFollowing.mockReturnValue({ isPending: false, isError: true, refetch });
    await renderForm();
    await fireEvent.press(screen.getByText('Try again'));
    expect(refetch).toHaveBeenCalled();
  });
});
