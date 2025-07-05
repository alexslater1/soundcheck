import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import { useAuthStore } from '@/features/auth/store';
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
  privatePostsCount: 0,
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

  afterEach(() => {
    useAuthStore.setState({ me: null });
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

  it('lets the glow show through the bar until the page scrolls under it', async () => {
    await render(<GigDetailScreen id="g1" />, { wrapper: Providers });
    await waitFor(() => expect(screen.getByTestId('act-bar')).toBeOnTheScreen());
    expect(screen.getByTestId('act-bar')).toHaveStyle({ backgroundColor: 'transparent' });

    await fireEvent.scroll(screen.getByTestId('gig-scroll'), {
      nativeEvent: { contentOffset: { x: 0, y: 300 } },
    });
    expect(screen.getByTestId('act-bar')).not.toHaveStyle({ backgroundColor: 'transparent' });

    await fireEvent.scroll(screen.getByTestId('gig-scroll'), {
      nativeEvent: { contentOffset: { x: 0, y: 0 } },
    });
    expect(screen.getByTestId('act-bar')).toHaveStyle({ backgroundColor: 'transparent' });
  });

  it('says how many times the viewer has seen the picked act, from their own Went gigs', async () => {
    useAuthStore.setState({ me: { id: 'me-1' } as never });
    // The API counts every one of them (more than a page of 50 here), not one page.
    const routed = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation(async (input, init) =>
      String(input).includes('/users/me-1/stats/gigs/count')
        ? json(200, { gigs: 53 })
        : routed(input, init),
    );
    await render(<GigDetailScreen id="g1" />, { wrapper: Providers });
    await waitFor(() => expect(screen.getByTestId('act-bar')).toBeOnTheScreen());

    await fireEvent.press(screen.getByRole('tab', { name: 'Cardinals, support' }));

    expect(await screen.findByText('You’ve seen Cardinals 53 times →')).toBeOnTheScreen();
    expect(requested('/users/me-1/stats/gigs/count')).toEqual([
      expect.stringContaining('artistId=cardinals'),
    ]);
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

  it("says when the venue's own listings are not read", async () => {
    const note =
      "We don't read this venue's own listings: its listings are a DICE ticketing widget, which we don't read. Its gigs here come from Ticketmaster and fans, so some may be missing.";
    const routed = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation(async (input, init) =>
      new URL(String(input), 'http://x').pathname.endsWith('/gigs/g1')
        ? json(200, {
            ...DETAIL,
            gig: { ...DETAIL.gig, venue: { ...DETAIL.gig.venue, coverageNote: note } },
          })
        : routed(input, init),
    );
    await render(<GigDetailScreen id="g1" />, { wrapper: Providers });

    expect(await screen.findByTestId('coverage-note')).toHaveTextContent(note);
  });

  it('counts the clips from private profiles the viewer cannot see, with no previews', async () => {
    const routed = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation(async (input, init) =>
      new URL(String(input), 'http://x').pathname.endsWith('/gigs/g1')
        ? json(200, { ...DETAIL, postsCount: 3, privatePostsCount: 3 })
        : routed(input, init),
    );
    await render(<GigDetailScreen id="g1" />, { wrapper: Providers });

    expect(await screen.findByTestId('gig-private-clips')).toHaveTextContent(
      '+3 clips from private profiles',
    );
    // Only private clips: the line takes the empty state's place.
    expect(screen.queryByTestId('gig-no-clips')).toBeNull();
  });

  it('says nothing about private clips when there are none hidden', async () => {
    await render(<GigDetailScreen id="g1" />, { wrapper: Providers });
    expect(await screen.findByTestId('gig-no-clips')).toBeOnTheScreen();
    expect(screen.queryByTestId('gig-private-clips')).toBeNull();
  });

  it('shows no note for a venue whose listings are read or listed', async () => {
    await render(<GigDetailScreen id="g1" />, { wrapper: Providers });
    await waitFor(() => expect(screen.getByText('LINE-UP')).toBeOnTheScreen());
    expect(screen.queryByTestId('coverage-note')).toBeNull();
  });
});
