import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { GigSetlist, numberSongs } from './GigSetlist';

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

const respond = (status: number, body: unknown) =>
  fetchMock.mockResolvedValue(
    new Response(JSON.stringify(body), {
      status,
      headers: { 'content-type': status < 400 ? 'application/json' : 'application/problem+json' },
    }),
  );

const SETLIST = {
  id: '7bd6a2a8',
  url: 'https://www.setlist.fm/setlist/pale-kites/2025/the-snug-7bd6a2a8.html',
  artist: 'Pale Kites',
  venue: 'The Snug',
  city: 'Manchester',
  date: '2025-10-17',
  tour: null,
  sets: [
    {
      title: null,
      songs: [
        { name: 'Intro', info: null, coverOf: null, tape: true },
        { name: 'Harbour Lights', info: null, coverOf: null, tape: false },
        { name: 'Paper Moon', info: 'acoustic', coverOf: 'Night Bus', tape: false },
      ],
    },
    { title: 'Encore', songs: [{ name: 'Last Train', info: null, coverOf: null, tape: false }] },
  ],
};

const PAST = '2025-10-17T19:30:00Z';

describe('GigSetlist', () => {
  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as unknown as typeof fetch;
  });

  it('numbers the songs through the encore, leaving the tape out', () => {
    const numbered = numberSongs(SETLIST.sets);
    expect(numbered.flatMap((p) => p.songs.map((s) => s.number))).toEqual([null, 1, 2, 3]);
  });

  it("shows a past gig's songs, set by set, with covers and the link back to setlist.fm", async () => {
    respond(200, SETLIST);
    await render(<GigSetlist gigId="g1" startsAt={PAST} />, { wrapper: Providers });

    await waitFor(() => expect(screen.getByTestId('gig-setlist')).toBeOnTheScreen());
    expect(String(fetchMock.mock.calls[0]![0])).toContain('/api/v1/gigs/g1/setlist');
    expect(screen.getByText('Harbour Lights')).toBeOnTheScreen();
    expect(screen.getByText('Night Bus cover · acoustic')).toBeOnTheScreen();
    expect(screen.getByText('ENCORE')).toBeOnTheScreen();
    expect(screen.getByText('3.')).toBeOnTheScreen(); // Last Train
    expect(screen.getByTestId('setlist-source')).toHaveTextContent('Setlist from setlist.fm →');
  });

  it('says when setlist.fm has none yet', async () => {
    respond(404, { status: 404, code: 'SETLIST_NOT_FOUND', detail: 'none' });
    await render(<GigSetlist gigId="g1" startsAt={PAST} />, { wrapper: Providers });

    expect(await screen.findByText(/No setlist on setlist.fm for this gig yet/)).toBeOnTheScreen();
  });

  it('leaves the section out without a setlist.fm key, and never asks for a gig still to come', async () => {
    respond(503, { status: 503, code: 'SETLISTS_UNAVAILABLE', detail: 'no key' });
    const view = await render(<GigSetlist gigId="g1" startsAt={PAST} />, { wrapper: Providers });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.queryByTestId('setlist-loading')).toBeNull());
    expect(screen.queryByText('SETLIST')).toBeNull();
    view.unmount();

    fetchMock.mockClear();
    await render(<GigSetlist gigId="g2" startsAt="2099-01-01T20:00:00Z" />, { wrapper: Providers });
    expect(screen.queryByText('SETLIST')).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
