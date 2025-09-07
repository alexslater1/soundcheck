import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import { useAuthStore } from '@/features/auth/store';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { CommentList } from './Comments';

const mockPush = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush }) }));
jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();

function Providers({ children }: PropsWithChildren) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  return (
    <QueryClientProvider client={client}>
      <ThemeProvider>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

const json = (status: number, body: unknown) =>
  new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: { 'content-type': status < 400 ? 'application/json' : 'application/problem+json' },
  });

const minutesAgo = (minutes: number) =>
  new Date(Date.now() - minutes * 60_000 - 5_000).toISOString();

const person = (id: string, handle: string, displayName: string) => ({
  id,
  handle,
  displayName,
  avatarUrl: null,
});

const page = {
  items: [
    {
      id: 'c1',
      postId: 'p1',
      author: person('maya-id', 'maya', 'Maya'),
      body: 'What a night',
      createdAt: minutesAgo(9),
    },
    {
      id: 'c2',
      postId: 'p1',
      author: person('me-id', 'me', 'Me'),
      body: 'Best gig this year',
      createdAt: minutesAgo(3 * 60),
    },
  ],
  nextCursor: null,
};

function respond(deleteStatus = 204) {
  fetchMock.mockImplementation(async (input, init) => {
    const url = String(input);
    if (init?.method === 'DELETE') return json(deleteStatus, { status: deleteStatus });
    if (url.includes('/comments')) return json(200, page);
    return json(200, {});
  });
}

beforeEach(() => {
  fetchMock.mockReset();
  mockPush.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  useAuthStore.setState({ me: { id: 'me-id' } as never });
});

const comments = () => screen.findAllByTestId('comment');

describe('CommentList', () => {
  it('shows how long ago each comment was written', async () => {
    respond();
    await render(<CommentList postId="p1" postAuthorId="author-id" />, { wrapper: Providers });

    const [first, second] = await comments();
    expect(within(first!).getByTestId('comment-time').props.children).toBe('9m');
    expect(within(first!).getByLabelText('9 minutes ago')).toBeOnTheScreen();
    expect(within(second!).getByTestId('comment-time').props.children).toBe('3h');
  });

  it("opens the commenter's profile from the avatar and from the name", async () => {
    respond();
    await render(<CommentList postId="p1" postAuthorId="author-id" />, { wrapper: Providers });
    await comments();

    await fireEvent.press(screen.getByLabelText('Maya, @maya'));
    await fireEvent.press(screen.getByText('@maya'));

    expect(mockPush).toHaveBeenCalledTimes(2);
    expect(mockPush).toHaveBeenCalledWith({ pathname: '/u/[handle]', params: { handle: 'maya' } });
  });

  it("on someone else's post, offers Delete on one's own comments and Report on others'", async () => {
    respond();
    await render(<CommentList postId="p1" postAuthorId="author-id" />, { wrapper: Providers });
    await comments();
    expect(screen.queryByText('Delete')).toBeNull(); // no big button any more

    await fireEvent.press(screen.getByLabelText('More for the comment by @maya'));
    expect(screen.queryByText('Delete comment')).toBeNull();
    expect(screen.getByText('Report comment')).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('sheet-backdrop', { includeHiddenElements: true }));

    await fireEvent.press(screen.getByLabelText('More for the comment by @me'));
    expect(screen.getByText('Delete comment')).toBeOnTheScreen();
    expect(screen.queryByText('Report comment')).toBeNull();
  });

  it('reports a comment with the reason chosen in the sheet', async () => {
    respond();
    await render(<CommentList postId="p1" postAuthorId="author-id" />, { wrapper: Providers });
    await comments();

    await fireEvent.press(screen.getByLabelText('More for the comment by @maya'));
    await fireEvent.press(screen.getByText('Report comment'));
    await fireEvent.press(screen.getByText('Spam'));
    await fireEvent.press(screen.getByTestId('report-send'));

    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([url]) => String(url).endsWith('/comments/c1/report')),
      ).toBe(true),
    );
    const [, init] = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/report'))!;
    expect(init?.method).toBe('POST');
    expect(JSON.parse(String(init?.body))).toEqual({ reason: 'SPAM' });
    expect(await screen.findByTestId('report-done')).toBeOnTheScreen();
  });

  it("offers ⋯ on every comment of one's own post, and deletes after a confirm", async () => {
    respond();
    await render(<CommentList postId="p1" postAuthorId="me-id" />, { wrapper: Providers });
    await comments();

    await fireEvent.press(screen.getByLabelText('More for the comment by @maya'));
    await fireEvent.press(screen.getByText('Delete comment'));
    expect(screen.getByText('Delete this comment?')).toBeOnTheScreen();
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'DELETE')).toBe(false);
    await fireEvent.press(screen.getByText('Delete'));

    await waitFor(() =>
      expect(fetchMock.mock.calls.find(([, init]) => init?.method === 'DELETE')?.[0]).toBe(
        'http://localhost:8088/api/v1/comments/c1',
      ),
    );
    // The list is read again after the delete.
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.filter(([url]) => String(url).includes('/posts/p1/comments')),
      ).toHaveLength(2),
    );
  });

  it('says so when a delete fails', async () => {
    respond(500);
    await render(<CommentList postId="p1" postAuthorId="me-id" />, { wrapper: Providers });
    await comments();

    await fireEvent.press(screen.getByLabelText('More for the comment by @me'));
    await fireEvent.press(screen.getByText('Delete comment'));
    await fireEvent.press(screen.getByText('Delete'));

    expect(
      await screen.findByText('That comment could not be deleted. Try again.'),
    ).toBeOnTheScreen();
  });
});
