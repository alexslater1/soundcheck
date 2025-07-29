import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import { useAuthStore } from '@/features/auth/store';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { GigComments } from './GigComments';

jest.mock('expo-router', () => ({ useRouter: () => ({ push: jest.fn() }) }));
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
  new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });

const person = (id: string, handle: string) => ({
  id,
  handle,
  displayName: handle,
  avatarUrl: null,
});
const at = '2026-09-01T20:00:00Z';
const thread = {
  items: [
    {
      id: 'c1',
      gigId: 'g1',
      author: person('maya-id', 'maya'),
      body: 'Who else?',
      createdAt: at,
      fromPrivateProfile: false,
    },
    { id: 'c2', gigId: 'g1', author: null, body: null, createdAt: at, fromPrivateProfile: true },
    {
      id: 'c3',
      gigId: 'g1',
      author: person('me-id', 'me'),
      body: 'Me!',
      createdAt: at,
      fromPrivateProfile: false,
    },
  ],
  nextCursor: null,
};

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  useAuthStore.setState({ me: { id: 'me-id' } as never });
  fetchMock.mockImplementation(async (_input, init) =>
    init?.method === 'POST' ? json(201, thread.items[2]) : json(200, thread),
  );
});

describe('GigComments: the thread under a gig', () => {
  it('asks "How was it?" after the gig and "Who\'s going?" before it', async () => {
    const { rerender } = await render(<GigComments gigId="g1" startsAt="2025-03-01T19:30:00Z" />, {
      wrapper: Providers,
    });
    expect(await screen.findByText('HOW WAS IT?')).toBeTruthy();
    await rerender(<GigComments gigId="g1" startsAt="2099-03-01T19:30:00Z" />);
    expect(screen.getByText("WHO'S GOING?")).toBeTruthy();
  });

  it("shows a private profile's comment without who or what, and offers Delete only on one's own", async () => {
    await render(<GigComments gigId="g1" startsAt="2025-03-01T19:30:00Z" />, {
      wrapper: Providers,
    });
    expect(await screen.findByText('Who else?')).toBeTruthy();
    expect(screen.getByTestId('comment-private')).toHaveTextContent(/A private profile commented/);
    expect(screen.queryByLabelText('More for the comment by @maya')).toBeNull();
    expect(screen.getByLabelText('More for the comment by @me')).toBeTruthy();
  });

  it('posts a comment to the gig with an idempotency key', async () => {
    await render(<GigComments gigId="g1" startsAt="2025-03-01T19:30:00Z" />, {
      wrapper: Providers,
    });
    await screen.findByText('Who else?');
    await fireEvent.changeText(screen.getByLabelText('Say how it was'), '  Loud and great ');
    await fireEvent.press(screen.getByRole('button', { name: 'Send' }));
    await waitFor(() =>
      expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(true),
    );
    const [url, init] = fetchMock.mock.calls.find(([, i]) => i?.method === 'POST')!;
    expect(String(url)).toContain('/api/v1/gigs/g1/comments');
    expect(JSON.parse(String(init?.body))).toEqual({ body: 'Loud and great' });
    expect(new Headers(init?.headers).get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
  });
});
