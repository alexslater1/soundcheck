import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { AlertGigsScreen } from './AlertGigsScreen';

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

const alertWith = (gigs: unknown[], gigCount: number) => ({
  id: 'n1',
  type: 'NEW_GIGS',
  gigs,
  gigCount,
  createdAt: '2026-10-03T05:15:00Z',
  read: false,
});

const KEO = {
  gigId: 'g1',
  title: 'Keo',
  startsAt: '2026-10-17T19:30:00Z',
  venueName: 'The Social',
  venueCity: 'London',
  reason: 'ARTIST',
  because: 'Keo',
};
const MOTH = {
  gigId: 'g2',
  title: 'Bleech 9:3',
  startsAt: '2026-11-07T19:00:00Z',
  venueName: 'MOTH Club',
  venueCity: 'London',
  reason: 'VENUE',
  because: 'MOTH Club',
};
const SEARCH = {
  gigId: 'g3',
  title: 'The North',
  startsAt: '2026-12-01T19:00:00Z',
  venueName: 'The Lexington',
  venueCity: null,
  reason: 'SEARCH',
  because: 'My bands in London',
};

beforeEach(() => {
  fetchMock.mockReset();
  mockPush.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

describe('AlertGigsScreen', () => {
  it('lists the new gigs with why each one is there, and opens a gig', async () => {
    fetchMock.mockResolvedValue(json(200, alertWith([KEO, MOTH, SEARCH], 3)));
    await render(<AlertGigsScreen id="n1" />, { wrapper: Providers });

    await waitFor(() => expect(screen.getByText('3 new gigs for you')).toBeOnTheScreen());
    expect(fetchMock.mock.calls[0]![0]).toBe('http://localhost:8088/api/v1/notifications/n1');
    expect(screen.getAllByTestId('alert-gig')).toHaveLength(3);
    expect(screen.getByText('You follow Keo')).toBeOnTheScreen();
    expect(screen.getByText('You follow MOTH Club')).toBeOnTheScreen();
    expect(screen.getByText('Your saved search “My bands in London”')).toBeOnTheScreen();
    expect(screen.getByText('Sat 17 Oct 2026 · The Social, London')).toBeOnTheScreen();

    await fireEvent.press(screen.getAllByTestId('alert-gig')[1]!);
    expect(mockPush).toHaveBeenCalledWith({ pathname: '/gig/[id]', params: { id: 'g2' } });
  });

  it('says how many more there are when the alert lists only some', async () => {
    fetchMock.mockResolvedValue(json(200, alertWith([KEO], 61)));
    await render(<AlertGigsScreen id="n1" />, { wrapper: Providers });

    await waitFor(() => expect(screen.getByText('61 new gigs for you')).toBeOnTheScreen());
    expect(screen.getByText('And 60 more: search the Gigs tab to see them all.')).toBeOnTheScreen();
  });

  it('shows an error with a retry when the alert cannot be loaded', async () => {
    fetchMock.mockResolvedValue(json(404, { code: 'NOTIFICATION_NOT_FOUND', status: 404 }));
    await render(<AlertGigsScreen id="n1" />, { wrapper: Providers });

    await waitFor(() => expect(screen.getByTestId('alert-error')).toBeOnTheScreen());
  });
});
