import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren, ReactNode } from 'react';

import { useAuthStore } from '@/features/auth/store';

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
  { id: 1, slug: 'rock', name: 'Rock', parentId: null, aliases: [] },
  { id: 2, slug: 'indie', name: 'Indie', parentId: 1, aliases: [] },
  { id: 17, slug: 'jazz', name: 'Jazz', parentId: null, aliases: [] },
];

const savedFilter = {
  id: 'f1',
  name: 'My bands in London',
  artists: [
    { id: 'a-keo', name: 'Keo' },
    { id: 'a-north', name: 'the north' },
  ],
  venues: [],
  genre: null,
  q: null,
  place: { kind: 'CITY', cityId: 7, cityName: 'London', lat: 51.5, lng: -0.12 },
};

type Route = (url: URL, init?: RequestInit) => Response | Promise<Response>;

/** Routes the fake network by URL; `gigs` decides what the gig list answers. */
function respondWith(gigs: Route, days: Route = () => json([]), saved: unknown[] = []) {
  fetchMock.mockImplementation(async (input, init) => {
    const url = new URL(String(input));
    if (url.pathname === '/api/v1/genres') return json(genres);
    if (url.pathname === '/api/v1/gigs') return gigs(url);
    if (url.pathname === '/api/v1/gigs/days') return days(url);
    if (url.pathname === '/api/v1/me/gig-filters') {
      return init?.method === 'POST' ? json({ ...savedFilter, id: 'f2' }) : json(saved);
    }
    if (url.pathname === '/api/v1/search/suggest') {
      return json([
        {
          kind: 'ARTIST',
          id: 'a9',
          name: 'Pale Kites',
          city: null,
          imageUrl: null,
          upcomingGigs: 3,
        },
        {
          kind: 'ARTIST',
          id: 'a8',
          name: 'Pale Waves',
          city: null,
          imageUrl: null,
          upcomingGigs: 0,
        },
        {
          kind: 'VENUE',
          id: 'v9',
          name: 'Palace Theatre',
          city: 'Manchester',
          imageUrl: null,
          upcomingGigs: 4,
        },
      ]);
    }
    if (url.pathname === '/api/v1/cities') {
      return json([
        { id: 9, name: 'Bristol', region: 'England', lat: 51.45, lng: -2.58, population: 1 },
      ]);
    }
    throw new Error(`unexpected request ${url}`);
  });
}

const gigRequests = () =>
  fetchMock.mock.calls
    .map(([input]) => new URL(String(input)))
    .filter((u) => u.pathname === '/api/v1/gigs');
const last = () => gigRequests().at(-1)!.searchParams;
/** Suggestions arrive after the 300 ms debounce and a request: allow for a busy test machine. */
const SLOW = { timeout: 4000 };

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  useAuthStore.setState({ me: { homeCity: { id: 1, name: 'Manchester' } } as never });
});

describe('GigsBrowser', () => {
  it('shows a skeleton, then the gigs of this month from today, near home', async () => {
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
    expect(screen.getByTestId('gigs-where')).toHaveTextContent('Within 25 km of Manchester');
    // No List view any more: the calendar is there from the start.
    expect(screen.getByTestId('gigs-calendar')).toBeOnTheScreen();
    expect(screen.queryByLabelText('List view')).toBeNull();
    const first = gigRequests()[0]!.searchParams;
    expect(Date.parse(first.get('from')!)).toBeGreaterThan(Date.now() - 25 * 60 * 60 * 1000);
    expect(first.get('limit')).toBe('20');
    expect(first.get('nationwide')).toBeNull();
  });

  it('only suggests while typing; a picked artist filters and keeps the place', async () => {
    respondWith(() => json({ items: [], nextCursor: null }));
    await render(<GigsBrowser />, { wrapper: Providers });
    await waitFor(() => expect(screen.getByTestId('gigs-empty')).toBeOnTheScreen());
    const before = gigRequests().length;

    await fireEvent.changeText(screen.getByLabelText('Search gigs'), 'pale');
    expect(await screen.findByLabelText('Artist: Pale Kites', {}, SLOW)).toBeOnTheScreen();
    expect(screen.getByText('3 upcoming')).toBeOnTheScreen();
    expect(gigRequests()).toHaveLength(before); // typing alone changes nothing

    await fireEvent.press(screen.getByLabelText('Artist: Pale Kites'));
    await waitFor(() => expect(last().getAll('artistIds')).toEqual(['a9']));
    expect(last().get('nationwide')).toBeNull();
    expect(last().get('q')).toBeNull();
    expect(screen.getByTestId('gigs-where')).toHaveTextContent('Within 25 km of Manchester');
  });

  it('takes several artists and a venue, and each can be removed', async () => {
    respondWith(() => json({ items: [], nextCursor: null }));
    await render(<GigsBrowser />, { wrapper: Providers });

    for (const label of ['Artist: Pale Kites', 'Artist: Pale Waves', 'Venue: Palace Theatre']) {
      await fireEvent.changeText(screen.getByLabelText('Search gigs'), 'pal');
      await fireEvent.press(await screen.findByLabelText(label, {}, SLOW));
    }
    await waitFor(() => expect(last().getAll('artistIds')).toEqual(['a9', 'a8']));
    expect(last().getAll('venueIds')).toEqual(['v9']);

    await fireEvent.press(screen.getByLabelText('Remove filter Pale Kites'));
    await waitFor(() => expect(last().getAll('artistIds')).toEqual(['a8']));
  });

  it('searches gig titles for the typed text when asked to', async () => {
    respondWith(() => json({ items: [], nextCursor: null }));
    await render(<GigsBrowser />, { wrapper: Providers });

    await fireEvent.changeText(screen.getByLabelText('Search gigs'), '  late show ');
    await fireEvent.press(await screen.findByTestId('search-text', {}, SLOW));

    await waitFor(() => expect(last().get('q')).toBe('late show'));
    expect(screen.getByLabelText('Remove filter “late show”')).toBeOnTheScreen();
  });

  it('looks UK-wide or near another city, and offers UK-wide when nothing is near', async () => {
    respondWith(() => json({ items: [], nextCursor: null }));
    await render(<GigsBrowser />, { wrapper: Providers });

    await fireEvent.press(await screen.findByTestId('gigs-uk-wide'));
    await waitFor(() => expect(last().get('nationwide')).toBe('true'));

    await fireEvent.press(screen.getByRole('radio', { name: 'Another city…' }));
    await fireEvent.changeText(screen.getByLabelText('Search cities'), 'Bri');
    await fireEvent.press(await screen.findByLabelText('City: Bristol', {}, SLOW));
    await waitFor(() => {
      expect(last().get('lat')).toBe('51.45');
      expect(last().get('lng')).toBe('-2.58');
      expect(last().get('radiusKm')).toBe('25');
      expect(last().get('nationwide')).toBeNull();
    });
    expect(screen.getByTestId('gigs-where')).toHaveTextContent('Within 25 km of Bristol');
  });

  it('applies a saved search, and saves the current one under a name', async () => {
    respondWith(
      () => json({ items: [], nextCursor: null }),
      () => json([]),
      [savedFilter],
    );
    await render(<GigsBrowser />, { wrapper: Providers });

    await fireEvent.press(
      await screen.findByLabelText('Saved search: My bands in London', {}, SLOW),
    );
    await waitFor(() => expect(last().getAll('artistIds')).toEqual(['a-keo', 'a-north']));
    expect(last().get('lat')).toBe('51.5');

    await fireEvent.press(screen.getByTestId('save-search'));
    expect(screen.getByLabelText('Name of the saved search')).toHaveDisplayValue(
      'Keo, the north · London',
    );
    await fireEvent.changeText(screen.getByLabelText('Name of the saved search'), 'Again');
    await fireEvent.press(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => {
      const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST');
      expect(JSON.parse(String(post?.[1]?.body))).toEqual({
        name: 'Again',
        artistIds: ['a-keo', 'a-north'],
        venueIds: [],
        genre: null,
        q: null,
        place: 'CITY',
        cityId: 7,
      });
    });
  });

  it('refetches with the genre slug', async () => {
    respondWith(() => json({ items: [], nextCursor: null }));
    await render(<GigsBrowser />, { wrapper: Providers });
    await waitFor(() => expect(screen.getByTestId('gigs-empty')).toBeOnTheScreen());

    // Only top-level genres are offered as chips.
    expect(screen.queryByRole('radio', { name: 'Indie' })).toBeNull();
    await fireEvent.press(screen.getByRole('radio', { name: 'Jazz' }));
    await waitFor(() => expect(last().get('genre')).toBe('jazz'));
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
    expect(last().get('cursor')).toBe('CURSOR-1');
    expect(screen.getByText('First Page Act')).toBeOnTheScreen(); // not replaced by a full-screen error
    expect(screen.queryByTestId('gigs-error')).toBeNull();

    failNext = false;
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.getByText('Second Page Act')).toBeOnTheScreen());
    expect(screen.getByText('First Page Act')).toBeOnTheScreen();
  });

  it('highlights the days with matching gigs, and lists one day when it is tapped', async () => {
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

    const highlighted = await screen.findByLabelText(/-17, 2 gigs$/, {}, SLOW);
    expect(dayRequests.length).toBeGreaterThan(0);
    await fireEvent.press(highlighted);

    await waitFor(() => {
      const span = Date.parse(last().get('to')!) - Date.parse(last().get('from')!);
      expect(span).toBeLessThanOrEqual(25 * 60 * 60 * 1000); // one UK day
    });
  });
});
