import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { billingLabel } from './ActBar';
import { GigDetailScreen } from './GigDetailScreen';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
jest.mock('expo-router', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  return {
    Link: ({ children }: { children: React.ReactNode }) =>
      React.createElement(React.Fragment, null, children),
    useRouter: () => ({ push: jest.fn() }),
  };
});

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
    headers: { 'content-type': status < 400 ? 'application/json' : 'application/problem+json' },
  });

const artist = (id: string, name: string) => ({
  id,
  name,
  genres: [],
  imageUrl: null,
  isFollowing: false,
});
const KEO = artist('keo', 'Keo');
const CARDINALS = artist('cardinals', 'Cardinals');
const PAST = '2025-03-01T19:30:00Z';

const DETAIL = {
  gig: {
    id: 'g1',
    title: 'Keo',
    startsAt: PAST,
    status: 'SCHEDULED',
    venue: { id: 'v1', name: 'Scala', city: 'London', location: null },
    headliner: KEO,
    support: [CARDINALS],
    imageUrl: null,
    ticketUrl: null,
    priceMin: null,
    priceMax: null,
    currency: null,
    myAttendance: null,
    distanceKm: null,
    userAdded: false,
  },
  lineup: [KEO, CARDINALS],
  attendanceCounts: { interested: 0, going: 0, went: 3 },
  postsCount: 0,
  sources: [],
};

const setlistOf = (artistName: string, song: string) => ({
  id: `sl-${artistName}`,
  url: null,
  artist: artistName,
  venue: 'Scala',
  city: 'London',
  date: '2025-03-01',
  tour: null,
  sets: [{ title: null, songs: [{ name: song, info: null, coverOf: null, tape: false }] }],
});

/** Answers by path, as the API would. */
function routeRequests() {
  fetchMock.mockImplementation(async (input) => {
    const url = new URL(String(input), 'http://test');
    const artistId = url.searchParams.get('artistId');
    if (url.pathname.endsWith('/gigs/g1')) return json(200, DETAIL);
    if (url.pathname.endsWith('/gigs/g1/setlist')) {
      return artistId === 'cardinals'
        ? json(404, { status: 404, code: 'SETLIST_NOT_FOUND' })
        : json(200, setlistOf('Keo', 'Harbour Lights'));
    }
    if (url.pathname.endsWith('/gigs/g1/posts')) return json(200, { items: [], nextCursor: null });
    if (url.pathname.endsWith('/genres')) return json(200, []);
    return json(404, { status: 404, code: 'NOT_FOUND' });
  });
}

const requested = (path: string) =>
  fetchMock.mock.calls.map(([input]) => String(input)).filter((url) => url.includes(path));

describe('GigDetailScreen act bar', () => {
  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as unknown as typeof fetch;
    routeRequests();
  });

  it('labels the first act the headliner and the others support', () => {
    expect([0, 1, 2].map(billingLabel)).toEqual(['Headliner', 'Support', 'Support']);
  });

  it('starts on the whole night: the line-up, the gig’s setlist and every clip', async () => {
    await render(<GigDetailScreen id="g1" />, { wrapper: Providers });

    await waitFor(() => expect(screen.getByTestId('act-bar')).toBeOnTheScreen());
    expect(screen.getAllByTestId('act-tab')).toHaveLength(3);
    expect(screen.getByRole('tab', { name: 'Whole night' })).toBeSelected();
    expect(screen.getByText('LINE-UP')).toBeOnTheScreen();
    expect(await screen.findByText('SETLIST · KEO')).toBeOnTheScreen();
    expect(requested('/gigs/g1/setlist')).toEqual([expect.not.stringContaining('artistId')]);
    expect(requested('/gigs/g1/posts')).toEqual([expect.not.stringContaining('artistId')]);
  });

  it('shows a support act’s own setlist and clips when it is picked, and goes back', async () => {
    await render(<GigDetailScreen id="g1" />, { wrapper: Providers });
    await waitFor(() => expect(screen.getByTestId('act-bar')).toBeOnTheScreen());

    await fireEvent.press(screen.getByRole('tab', { name: 'Cardinals, support' }));

    expect(screen.getByRole('tab', { name: 'Cardinals, support' })).toBeSelected();
    expect(screen.getByTestId('act-billing')).toHaveTextContent('SUPPORT');
    expect(screen.getByText('CLIPS OF CARDINALS')).toBeOnTheScreen();
    expect(screen.queryByText('LINE-UP')).toBeNull();
    expect(
      await screen.findByText(/No setlist for Cardinals at this gig on setlist.fm yet/),
    ).toBeOnTheScreen();
    expect(await screen.findByText(/No clips of Cardinals from this gig yet/)).toBeOnTheScreen();
    expect(requested('artistId=cardinals')).toEqual([
      expect.stringContaining('/gigs/g1/setlist?artistId=cardinals'),
      expect.stringContaining('/gigs/g1/posts?'),
    ]);

    await fireEvent.press(screen.getByRole('tab', { name: 'Whole night' }));
    expect(screen.getByText('LINE-UP')).toBeOnTheScreen();
  });

  it('has no bar for a gig with one act', async () => {
    fetchMock.mockImplementation(async (input) => {
      const url = String(input);
      if (url.includes('/gigs/g1/setlist'))
        return json(404, { status: 404, code: 'SETLIST_NOT_FOUND' });
      if (url.includes('/gigs/g1/posts')) return json(200, { items: [], nextCursor: null });
      if (url.includes('/gigs/g1')) {
        return json(200, { ...DETAIL, lineup: [KEO], gig: { ...DETAIL.gig, support: [] } });
      }
      return json(200, []);
    });
    await render(<GigDetailScreen id="g1" />, { wrapper: Providers });

    await waitFor(() => expect(screen.getByText('LINE-UP')).toBeOnTheScreen());
    expect(screen.queryByTestId('act-bar')).toBeNull();
  });
});
