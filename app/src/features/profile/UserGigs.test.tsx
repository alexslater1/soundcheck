import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react-native';
import type { PropsWithChildren, ReactNode } from 'react';

import { ThemeProvider } from '@/theme/ThemeProvider';

import { UserGigs } from './UserGigs';

jest.mock('expo-router', () => ({ Link: ({ children }: { children: ReactNode }) => children }));
jest.mock('expo-image', () => ({ Image: () => null }));

const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();

const gig = (id: string, startsAt: string) => ({
  id,
  title: `Gig ${id}`,
  startsAt,
  status: 'SCHEDULED',
  venue: { id: 'v', name: 'Venue', city: null },
  headliner: { id: 'h', name: 'H', genres: [], isFollowing: false },
  support: [],
  userAdded: false,
});

const json = (body: unknown) =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  });

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

describe("UserGigs: someone's Gigs tab", () => {
  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as unknown as typeof fetch;
  });

  it('lists the gigs they are going to, then the ones they went to', async () => {
    fetchMock.mockImplementation(async (input) => {
      const url = String(input);
      if (url.includes('/api/v1/users/u2/gigs/going')) {
        return json({ items: [gig('soon', '2026-11-01T19:00:00Z')], nextCursor: null });
      }
      if (url.includes('/api/v1/users/u2/stats/gigs')) {
        return json({ items: [gig('past', '2026-09-01T19:00:00Z')], nextCursor: null });
      }
      throw new Error(`unexpected request ${url}`);
    });
    await render(<UserGigs userId="u2" />, { wrapper: Providers });

    expect(await screen.findByText('Gig soon')).toBeTruthy();
    expect(await screen.findByText('Gig past')).toBeTruthy();
    expect(screen.getByTestId('user-gigs-going')).toHaveTextContent(/Gig soon/);
    expect(screen.getByTestId('user-gigs-went')).toHaveTextContent(/Gig past/);
    // Nothing Interested: only the two lists are asked for.
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('says so when a list is empty', async () => {
    fetchMock.mockImplementation(async () => json({ items: [], nextCursor: null }));
    await render(<UserGigs userId="u2" />, { wrapper: Providers });
    expect(await screen.findByText('No gigs coming up.')).toBeTruthy();
    expect(await screen.findByText('No gigs marked as Went yet.')).toBeTruthy();
  });
});
