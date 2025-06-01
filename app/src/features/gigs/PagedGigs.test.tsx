import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react-native';
import type { PropsWithChildren, ReactNode } from 'react';

import type { GigSummary } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { PagedGigs } from './PagedGigs';

jest.mock('expo-router', () => ({ Link: ({ children }: { children: ReactNode }) => children }));
jest.mock('expo-image', () => ({ Image: () => null }));

const gig = (id: string) =>
  ({
    id,
    title: `Gig ${id}`,
    startsAt: '2026-10-17T19:00:00Z',
    status: 'SCHEDULED',
    venue: { id: 'v', name: 'Venue', city: null },
    headliner: { id: 'h', name: 'H', genres: [], isFollowing: false },
    support: [],
    userAdded: false,
  }) as unknown as GigSummary;

function Providers({ children }: PropsWithChildren) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <ThemeProvider>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

describe('PagedGigs', () => {
  it('shows the first page and loads the next with the cursor on request', async () => {
    const fetchPage = jest.fn(async (cursor: string | undefined) =>
      cursor === 'C1'
        ? { items: [gig('3')], nextCursor: null }
        : { items: [gig('1'), gig('2')], nextCursor: 'C1' },
    );
    await render(
      <PagedGigs queryKey={['test-gigs']} fetchPage={fetchPage} empty="None" testID="paged" />,
      { wrapper: Providers },
    );

    expect(await screen.findByText('Gig 2')).toBeTruthy();
    await fireEvent.press(screen.getByText('Show more gigs'));

    expect(await screen.findByText('Gig 3')).toBeTruthy();
    expect(fetchPage).toHaveBeenLastCalledWith('C1');
    expect(screen.queryByText('Show more gigs')).toBeNull();
  });

  it('says so when there are none', async () => {
    await render(
      <PagedGigs
        queryKey={['none']}
        fetchPage={async () => ({ items: [], nextCursor: null })}
        empty="No upcoming gigs listed."
      />,
      { wrapper: Providers },
    );
    expect(await screen.findByText('No upcoming gigs listed.')).toBeTruthy();
  });
});
