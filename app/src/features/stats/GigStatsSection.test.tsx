import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import type { GigStats } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { GigStatsSection, statsGigsHref, yearRows } from './GigStatsSection';

const mockPush = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush }) }));

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

// The shape of a real history: an act seen as headliner and support, two bills on one night.
const STATS: GigStats = {
  totals: { gigs: 4, acts: 12, venues: 3, cities: 1 },
  currentYear: 2026,
  years: [
    { year: 2026, gigs: 3, acts: 9, venues: 2 },
    { year: 2025, gigs: 1, acts: 3, venues: 1 },
  ],
  acts: [
    {
      id: 'keo',
      name: 'Keo',
      imageUrl: null,
      times: 3,
      first: '2025-03-01T19:00:00Z',
      last: '2026-02-10T20:00:00Z',
    },
    {
      id: 'cardinals',
      name: 'Cardinals',
      imageUrl: null,
      times: 2,
      first: '2026-03-01T19:00:00Z',
      last: '2026-03-01T22:00:00Z',
    },
  ],
  venues: [
    {
      id: 'scala',
      name: 'Scala',
      city: 'London',
      times: 2,
      first: '2025-03-01T19:00:00Z',
      last: '2026-01-10T20:00:00Z',
    },
  ],
};

describe('GigStatsSection', () => {
  beforeEach(() => {
    fetchMock.mockReset();
    mockPush.mockReset();
    globalThis.fetch = fetchMock as unknown as typeof fetch;
  });

  it('shows the totals, this year against last, and the most-seen acts and venues', async () => {
    fetchMock.mockResolvedValue(json(200, STATS));
    await render(<GigStatsSection userId="u1" own />, { wrapper: Providers });

    await waitFor(() => expect(screen.getByTestId('stats')).toBeOnTheScreen());
    expect(String(fetchMock.mock.calls[0]![0])).toContain('/api/v1/users/u1/stats?top=10');
    expect(screen.getByText('YOUR STATS')).toBeOnTheScreen();
    expect(screen.getByLabelText('4 gigs')).toBeOnTheScreen();
    expect(screen.getByLabelText('12 acts')).toBeOnTheScreen();
    expect(screen.getByLabelText('1 city')).toBeOnTheScreen();
    expect(screen.getByTestId('stats-year')).toHaveTextContent(
      '3 gigs in 2026 so far · 2 more than 2025',
    );

    // Gigs per year: the busiest year is a full bar, the other a third of it (3 vs 1).
    expect(screen.getByLabelText('2026 so far: 3 gigs')).toBeOnTheScreen();
    expect(screen.getByLabelText('2025: 1 gig')).toBeOnTheScreen();
    const bars = screen.getAllByTestId('stats-year-bar');
    expect(bars[0]).toHaveStyle({ width: '100%' });
    expect(bars[1]).toHaveStyle({ width: `${(1 / 3) * 100}%` });

    const acts = screen.getAllByTestId('stats-act');
    expect(acts).toHaveLength(2);
    expect(acts[0]).toHaveTextContent(/Keo.*First 1 Mar 2025 · last 10 Feb 2026.*3×/);
    expect(acts[1]).toHaveTextContent(/Cardinals.*2 times on 1 Mar 2026.*2×/);
    expect(screen.getByTestId('stats-venue')).toHaveTextContent(/Scala, London/);
  });

  it('opens the gigs behind a line', async () => {
    fetchMock.mockResolvedValue(json(200, STATS));
    await render(<GigStatsSection userId="u1" own />, { wrapper: Providers });
    await waitFor(() => expect(screen.getByTestId('stats')).toBeOnTheScreen());

    await fireEvent.press(screen.getAllByTestId('stats-act')[1]!);
    expect(mockPush).toHaveBeenCalledWith({
      pathname: '/stats/[userId]',
      params: { userId: 'u1', artistId: 'cardinals', title: 'Cardinals' },
    });
    await fireEvent.press(screen.getByTestId('stats-venue'));
    expect(mockPush).toHaveBeenLastCalledWith(
      statsGigsHref('u1', { venueId: 'scala', title: 'Scala' }),
    );
  });

  it('loads every act and venue on request when the lists are cut', async () => {
    fetchMock.mockResolvedValue(json(200, STATS));
    await render(<GigStatsSection userId="u1" own />, { wrapper: Providers });
    await waitFor(() => expect(screen.getByTestId('stats')).toBeOnTheScreen());

    await fireEvent.press(screen.getByText('Show all acts and venues'));
    await waitFor(() =>
      expect(String(fetchMock.mock.calls.at(-1)![0])).toContain('/api/v1/users/u1/stats?top=100'),
    );
  });

  it('has an empty state that says how stats are made, for one’s own profile', async () => {
    fetchMock.mockResolvedValue(
      json(200, {
        totals: { gigs: 0, acts: 0, venues: 0, cities: 0 },
        currentYear: 2026,
        years: [],
        acts: [],
        venues: [],
      }),
    );
    await render(<GigStatsSection userId="u1" own />, { wrapper: Providers });
    await waitFor(() => expect(screen.getByTestId('stats-empty')).toBeOnTheScreen());
    expect(screen.getByTestId('stats-empty')).toHaveTextContent(/Mark the gigs you went to/);
    expect(screen.queryByTestId('stats-year-row')).toBeNull();
    expect(screen.queryByText('Show all acts and venues')).toBeNull();
  });

  it('never shows one person’s stats while another person’s load', async () => {
    let answer: (response: Response) => void = () => {};
    fetchMock
      .mockResolvedValueOnce(json(200, STATS))
      .mockReturnValueOnce(new Promise<Response>((resolve) => (answer = resolve)));
    const view = await render(<GigStatsSection userId="u1" own={false} />, { wrapper: Providers });
    await waitFor(() => expect(screen.getByTestId('stats')).toBeOnTheScreen());

    await view.rerender(<GigStatsSection userId="u2" own={false} />);

    expect(screen.queryByTestId('stats')).toBeNull();
    expect(screen.getByTestId('stats-loading')).toBeOnTheScreen();
    answer(json(200, { ...STATS, acts: [], venues: [], years: [] }));
  });

  it('offers a retry when the stats cannot be loaded', async () => {
    fetchMock.mockResolvedValue(json(500, { status: 500, code: 'INTERNAL' }));
    await render(<GigStatsSection userId="u2" own={false} />, { wrapper: Providers });
    await waitFor(() => expect(screen.getByText('Could not load the stats')).toBeOnTheScreen());
    expect(screen.getByText('STATS')).toBeOnTheScreen();
  });
});

describe('yearRows', () => {
  it('runs from this year back, with the years without gigs as 0', () => {
    expect(
      yearRows(2026, [
        { year: 2022, gigs: 3 },
        { year: 2019, gigs: 12 },
      ]).map((r) => `${r.label}:${r.gigs}${r.current ? '*' : ''}`),
    ).toEqual(['2026:0*', '2025:0', '2024:0', '2023:0', '2022:3', '2021:0', '2020:0', '2019:12']);
    expect(yearRows(2026, [])).toEqual([]);
  });

  it('gives ten years a row each and sums anything older as Earlier', () => {
    const rows = yearRows(2026, [
      { year: 2025, gigs: 2 },
      { year: 1979, gigs: 1 },
    ]);
    expect(rows).toHaveLength(11);
    expect(rows[9]!.label).toBe('2017');
    expect(rows[10]).toEqual({ label: 'Earlier', gigs: 1, current: false });
  });
});
