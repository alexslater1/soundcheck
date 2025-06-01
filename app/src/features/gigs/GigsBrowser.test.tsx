import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren, ReactNode } from 'react';

import { GigsBrowser } from './GigsBrowser';

// The cards link to /gig/[id]; outside a navigator the link just renders its child.
jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
jest.mock('expo-image', () => ({ Image: () => null }));
jest.mock('expo-router', () => ({
  Link: ({ children }: { children: ReactNode }) => children,
  // Outside a navigator "focus" is simply "mounted".
  useFocusEffect: (effect: () => void) =>
    jest.requireActual<typeof import('react')>('react').useEffect(effect, [effect]),
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
  userAdded: false,
});

const genres = [
  { id: 1, slug: 'rock', name: 'Rock', parentId: null },
  { id: 2, slug: 'indie', name: 'Indie', parentId: 1 },
  { id: 17, slug: 'jazz', name: 'Jazz', parentId: null },
];

/** Routes the fake network by URL; `gigs` decides what the gig list answers. */
function respondWith(
  gigs: (url: URL) => Response | Promise<Response>,
  days: (url: URL) => Response = () => json([]),
) {
  fetchMock.mockImplementation(async (input) => {
    const url = new URL(String(input));
    if (url.pathname === '/api/v1/genres') return json(genres);
    if (url.pathname === '/api/v1/gigs') return gigs(url);
    if (url.pathname === '/api/v1/gigs/days') return days(url);
    if (url.pathname === '/api/v1/artists') {
      return json({
        items: [{ id: 'a9', name: 'Pale Kites', imageUrl: null, genres: [], isFollowing: false }],
        nextCursor: null,
      });
    }
    if (url.pathname === '/api/v1/venues') {
      return json([{ id: 'v9', name: 'Palace Theatre', city: 'Manchester', location: null }]);
    }
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
    expect(screen.getByText('The Tin Exchange, Manchester')).toBeOnTheScreen();
    expect(screen.getByText('19:30 · 1.2 km · £12.50')).toBeOnTheScreen();
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
    expect(screen.queryByRole('radio', { name: 'Indie' })).toBeNull();
    await fireEvent.press(screen.getByRole('radio', { name: 'Jazz' }));
    await waitFor(() => expect(gigRequests().at(-1)!.searchParams.get('genre')).toBe('jazz'));

    await fireEvent.changeText(screen.getByLabelText('Search gigs'), '  wolves ');
    await waitFor(() => expect(gigRequests().at(-1)!.searchParams.get('q')).toBe('wolves'));

    await fireEvent.press(screen.getByRole('radio', { name: 'Tonight' }));
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

  it('asks for the next page with the cursor, and a failed page keeps what is already shown', async () => {
    let failNext = false;
    respondWith((url) => {
      const cursor = url.searchParams.get('cursor');
      if (!cursor) return json({ items: [gig('g1', 'First Page Act')], nextCursor: 'CURSOR-1' });
      if (failNext) throw new TypeError('Failed to fetch');
      return json({ items: [gig('g2', 'Second Page Act')], nextCursor: null });
    });
    await render(<GigsBrowser />, { wrapper: Providers });
    await waitFor(() => expect(screen.getByText('First Page Act')).toBeOnTheScreen());

    failNext = true;
    await fireEvent(screen.getByTestId('gig-list'), 'endReached');
    await waitFor(() => expect(screen.getByTestId('gigs-more-error')).toBeOnTheScreen());
    expect(gigRequests().at(-1)!.searchParams.get('cursor')).toBe('CURSOR-1');
    expect(screen.getByText('First Page Act')).toBeOnTheScreen(); // not replaced by a full-screen error
    expect(screen.queryByTestId('gigs-error')).toBeNull();

    failNext = false;
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.getByText('Second Page Act')).toBeOnTheScreen());
    expect(screen.getByText('First Page Act')).toBeOnTheScreen();
  });

  it('turns a suggested artist into an exact, UK-wide filter that can be removed', async () => {
    respondWith(() => json({ items: [], nextCursor: null }));
    await render(<GigsBrowser />, { wrapper: Providers });

    await fireEvent.changeText(screen.getByLabelText('Search gigs'), 'pale');
    await fireEvent.press(await screen.findByLabelText('Artist: Pale Kites'));

    await waitFor(() => {
      const last = gigRequests().at(-1)!;
      expect(last.searchParams.get('artistId')).toBe('a9');
      expect(last.searchParams.get('nationwide')).toBe('true');
      expect(last.searchParams.get('q')).toBeNull();
    });
    await fireEvent.press(screen.getByLabelText('Remove filter Pale Kites'));
    await waitFor(() => {
      const last = gigRequests().at(-1)!;
      expect(last.searchParams.get('artistId')).toBeNull();
      expect(last.searchParams.get('nationwide')).toBeNull();
    });
  });

  it('shows a calendar of the days with matching gigs, and lists one day when it is tapped', async () => {
    const dayRequests: URL[] = [];
    respondWith(
      () => json({ items: [gig('g1', 'Harbour Wolves')], nextCursor: null }),
      (url) => {
        dayRequests.push(url);
        const month = new Date(url.searchParams.get('from')!);
        // The 17th of the month the calendar shows (UK midnight is the evening before in UTC in summer).
        const shown = new Date(month.getTime() + 12 * 60 * 60 * 1000);
        const iso = `${shown.getUTCFullYear()}-${String(shown.getUTCMonth() + 1).padStart(2, '0')}-17`;
        return json([{ date: iso, count: 2 }]);
      },
    );
    await render(<GigsBrowser />, { wrapper: Providers });
    await fireEvent.press(screen.getByLabelText('Calendar view'));

    const highlighted = await screen.findByLabelText(/-17, 2 gigs$/);
    expect(screen.getByTestId('gigs-calendar')).toBeOnTheScreen();
    expect(dayRequests.length).toBeGreaterThan(0);
    await fireEvent.press(highlighted);

    await waitFor(() => {
      const last = gigRequests().at(-1)!;
      const span =
        Date.parse(last.searchParams.get('to')!) - Date.parse(last.searchParams.get('from')!);
      expect(span).toBeLessThanOrEqual(25 * 60 * 60 * 1000); // one UK day
    });
  });
});
