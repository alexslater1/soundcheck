import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren, ReactNode } from 'react';

import { GigsBrowser } from './GigsBrowser';

// The cards link to /gig/[id]; outside a navigator the link just renders its child.
jest.mock('expo-router', () => ({
  Link: ({ children }: { children: ReactNode }) => children,
}));

const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();

function Providers({ children }: PropsWithChildren) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

const json = (body: unknown) =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  });

const gig = (id: string, title: string) => ({
  id,
  title,
  startsAt: '2026-10-03T18:30:00Z',
  status: 'SCHEDULED',
  venue: { id: 'v1', name: 'The Tin Exchange', city: 'Manchester', location: null },
  headliner: { id: 'a1', name: title, imageUrl: null, genres: ['indie'], isFollowing: false },
  support: [],
  imageUrl: null,
  ticketUrl: null,
  priceMin: 12.5,
  priceMax: 12.5,
  currency: 'GBP',
  myAttendance: null,
  distanceKm: 1.2,
});

const genres = [
  { id: 1, slug: 'rock', name: 'Rock', parentId: null },
  { id: 2, slug: 'indie', name: 'Indie', parentId: 1 },
  { id: 17, slug: 'jazz', name: 'Jazz', parentId: null },
];

/** Routes the fake network by URL; `gigs` decides what the gig list answers. */
function respondWith(gigs: (url: URL) => Response | Promise<Response>) {
  fetchMock.mockImplementation(async (input) => {
    const url = new URL(String(input));
    if (url.pathname === '/api/v1/genres') return json(genres);
    if (url.pathname === '/api/v1/gigs') return gigs(url);
    throw new Error(`unexpected request ${url}`);
  });
}

const gigRequests = () =>
  fetchMock.mock.calls
    .map(([input]) => new URL(String(input)))
    .filter((u) => u.pathname === '/api/v1/gigs');

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

describe('GigsBrowser', () => {
  it('shows a skeleton, then the gigs with distance and price', async () => {
    // The list answer is held back by hand so the loading state is observable whatever the timing.
    let answer: (response: Response) => void = () => {};
    respondWith(() => new Promise<Response>((resolve) => (answer = resolve)));

    await render(<GigsBrowser />, { wrapper: Providers });

    await waitFor(() => expect(gigRequests()).toHaveLength(1));
    expect(screen.getByTestId('gigs-loading')).toBeOnTheScreen();
    answer(json({ items: [gig('g1', 'Harbour Wolves')], nextCursor: null }));
    await waitFor(() => expect(screen.getByText('Harbour Wolves')).toBeOnTheScreen());
    expect(screen.getByText('The Tin Exchange, Manchester · 1.2 km · £12.50')).toBeOnTheScreen();
    // The default chip is "Next 7 days": a from/to window seven days apart, page size 20.
    const first = gigRequests()[0]!;
    const from = Date.parse(first.searchParams.get('from')!);
    const to = Date.parse(first.searchParams.get('to')!);
    expect(to - from).toBe(7 * 24 * 60 * 60 * 1000);
    expect(first.searchParams.get('limit')).toBe('20');
  });

  it('refetches with the genre slug and the search text', async () => {
    respondWith(() => json({ items: [], nextCursor: null }));
    await render(<GigsBrowser />, { wrapper: Providers });
    await waitFor(() => expect(screen.getByTestId('gigs-empty')).toBeOnTheScreen());

    // Only top-level genres are offered as chips.
    expect(screen.queryByRole('button', { name: 'Indie' })).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Jazz' }));
    await waitFor(() => expect(gigRequests().at(-1)!.searchParams.get('genre')).toBe('jazz'));

    await fireEvent.changeText(screen.getByLabelText('Search gigs'), '  wolves ');
    await waitFor(() => expect(gigRequests().at(-1)!.searchParams.get('q')).toBe('wolves'));

    await fireEvent.press(screen.getByRole('button', { name: 'Tonight' }));
    await waitFor(() => {
      const last = gigRequests().at(-1)!;
      const span =
        Date.parse(last.searchParams.get('to')!) - Date.parse(last.searchParams.get('from')!);
      expect(span).toBeLessThanOrEqual(28 * 60 * 60 * 1000);
    });
  });

  it('shows an error with a retry that works', async () => {
    let fail = true;
    respondWith(() => {
      if (fail) throw new TypeError('Failed to fetch');
      return json({ items: [gig('g1', 'Pale Kites')], nextCursor: null });
    });
    await render(<GigsBrowser />, { wrapper: Providers });
    await waitFor(() => expect(screen.getByTestId('gigs-error')).toBeOnTheScreen());

    fail = false;
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));

    await waitFor(() => expect(screen.getByText('Pale Kites')).toBeOnTheScreen());
  });
});
