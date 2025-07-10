import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import { useListNotifications } from '@/api/generated/notifications/notifications';
import { Text } from '@/components/ui/Text';
import { fireEvent } from '@/test/fireEvent';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { ActivityScreen } from './ActivityScreen';
import { withEntryRead } from './openNotification';

const mockPush = jest.fn();
jest.mock('expo-router', () => ({
  useRouter: () => ({ push: mockPush }),
  useFocusEffect: jest.fn(),
}));

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

/** What the Activity tab's badge reads: its own one-item query of the inbox. */
function Badge() {
  const inbox = useListNotifications({ limit: 1 });
  return <Text testID="badge">{String(inbox.data?.unreadCount ?? '')}</Text>;
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': status < 400 ? 'application/json' : 'application/problem+json' },
  });

const minutesAgo = (minutes: number) =>
  new Date(Date.now() - minutes * 60_000 - 5_000).toISOString();
const maya = { id: 'u1', handle: 'maya', displayName: 'Maya', avatarUrl: null };

/** A small fake server: the inbox, and marking one entry read. */
function server(entries: { id: string; read: boolean; [key: string]: unknown }[]) {
  const state = entries.map((e) => ({ ...e }));
  const inbox = () => ({
    items: state,
    nextCursor: null,
    unreadCount: state.filter((e) => !e.read).length,
  });
  fetchMock.mockImplementation(async (input, init) => {
    const url = String(input);
    const read = url.match(/\/notifications\/([^/]+)\/read$/);
    if (init?.method === 'POST' && read) {
      const entry = state.find((e) => e.id === read[1]);
      if (entry) entry.read = true;
      return json(200, { unreadCount: inbox().unreadCount });
    }
    return json(200, inbox());
  });
}

const posts = () => fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST');

beforeEach(() => {
  fetchMock.mockReset();
  mockPush.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

describe('ActivityScreen', () => {
  it('opens an entry, marks that one read, and the badge counts one fewer', async () => {
    server([
      {
        id: 'n2',
        type: 'POST_LIKED',
        actor: maya,
        postId: 'p1',
        createdAt: minutesAgo(9),
        read: false,
      },
      { id: 'n1', type: 'NEW_FOLLOWER', actor: maya, createdAt: minutesAgo(3 * 60), read: false },
    ]);
    await render(
      <>
        <Badge />
        <ActivityScreen />
      </>,
      { wrapper: Providers },
    );
    const rows = await screen.findAllByTestId('notification');
    expect(screen.getByTestId('badge').props.children).toBe('2');
    expect(screen.getAllByTestId('unread-dot')).toHaveLength(2);

    await fireEvent.press(rows[0]!);

    expect(mockPush).toHaveBeenCalledWith({ pathname: '/post/[id]', params: { id: 'p1' } });
    // At once, before the server answers: the dot goes and the badge drops.
    expect(
      within(screen.getAllByTestId('notification')[0]!).queryByTestId('unread-dot'),
    ).toBeNull();
    expect(screen.getByTestId('badge').props.children).toBe('1');
    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(posts()[0]![0]).toBe('http://localhost:8088/api/v1/notifications/n2/read');
    // The other entry stays unread, also after the inbox is read again.
    await waitFor(() => expect(screen.getAllByTestId('unread-dot')).toHaveLength(1));
    expect(screen.getByTestId('badge').props.children).toBe('1');
  });

  it('sends nothing for an entry already read, and still opens it', async () => {
    server([
      { id: 'n1', type: 'NEW_FOLLOWER', actor: maya, createdAt: minutesAgo(30), read: true },
    ]);
    await render(<ActivityScreen />, { wrapper: Providers });

    await fireEvent.press(await screen.findByTestId('notification'));

    expect(mockPush).toHaveBeenCalledWith({ pathname: '/u/[handle]', params: { handle: 'maya' } });
    expect(posts()).toHaveLength(0);
  });

  it('marks a new-gig alert read when it is opened', async () => {
    server([
      {
        id: 'alert-1',
        type: 'NEW_GIGS',
        gigs: [],
        gigCount: 2,
        createdAt: minutesAgo(2 * 24 * 60),
        read: false,
      },
    ]);
    await render(<ActivityScreen />, { wrapper: Providers });

    await fireEvent.press(await screen.findByTestId('notification'));

    expect(mockPush).toHaveBeenCalledWith({ pathname: '/alerts/[id]', params: { id: 'alert-1' } });
    await waitFor(() =>
      expect(posts()[0]?.[0]).toBe('http://localhost:8088/api/v1/notifications/alert-1/read'),
    );
  });

  it('says how long ago each entry happened', async () => {
    server([
      {
        id: 'n2',
        type: 'POST_LIKED',
        actor: maya,
        postId: 'p1',
        createdAt: minutesAgo(9),
        read: false,
      },
      {
        id: 'n1',
        type: 'NEW_FOLLOWER',
        actor: maya,
        createdAt: minutesAgo(2 * 24 * 60),
        read: true,
      },
    ]);
    await render(<ActivityScreen />, { wrapper: Providers });

    await screen.findAllByTestId('notification');
    expect(screen.getAllByTestId('notification-time').map((t) => t.props.children)).toEqual([
      '9m',
      '2d',
    ]);
    expect(screen.getByLabelText('@maya liked your clip, 9 minutes ago, unread')).toBeOnTheScreen();
    expect(screen.getByLabelText('@maya started following you, 2 days ago')).toBeOnTheScreen();
  });
});

describe('withEntryRead', () => {
  it('marks the entry read and counts one fewer, never below zero', () => {
    const inbox = {
      items: [
        { id: 'a', read: false },
        { id: 'b', read: false },
      ] as never,
      nextCursor: null,
      unreadCount: 1,
    };
    const after = withEntryRead(inbox, 'a');
    expect(after.items.map((n) => n.read)).toEqual([true, false]);
    expect(after.unreadCount).toBe(0);
    expect(withEntryRead(after, 'b').unreadCount).toBe(0);
  });
});
