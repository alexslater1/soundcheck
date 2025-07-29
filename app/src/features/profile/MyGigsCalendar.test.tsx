import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react-native';
import type { PropsWithChildren, ReactNode } from 'react';

import { isoDate, monthOf, monthTitle, monthWindow } from '@/features/calendar/monthGrid';
import { formatGigDate } from '@/features/gigs/format';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { MyGigsCalendar } from './MyGigsCalendar';

jest.mock('expo-router', () => ({ Link: ({ children }: { children: ReactNode }) => children }));
jest.mock('expo-image', () => ({ Image: () => null }));
jest.mock('@/features/location/deviceLocation', () => ({
  useDistanceOrigin: () => ({ params: {}, ready: true, here: null }),
}));

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

const requests = () => fetchMock.mock.calls.map(([input]) => new URL(String(input)));

describe('MyGigsCalendar: My gigs, all time first', () => {
  beforeEach(() => {
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as unknown as typeof fetch;
  });

  it('opens on every gig, newest first under month headers, and loads the next page on request', async () => {
    fetchMock.mockImplementation(async (input) => {
      const url = new URL(String(input));
      if (url.searchParams.get('cursor') === 'c2') {
        return json({ items: [gig('old', '2024-06-10T19:00:00Z')], nextCursor: null });
      }
      return json({
        items: [gig('new', '2026-10-02T19:00:00Z'), gig('aug', '2026-08-20T19:00:00Z')],
        nextCursor: 'c2',
      });
    });
    await render(<MyGigsCalendar />, { wrapper: Providers });

    expect(await screen.findByText('Gig new')).toBeTruthy();
    expect(screen.getByTestId('my-gigs-month-2026-10')).toHaveTextContent('OCTOBER 2026');
    expect(screen.getByTestId('my-gigs-month-2026-08')).toHaveTextContent('AUGUST 2026');
    const first = requests()[0]!;
    expect(first.pathname).toBe('/api/v1/me/gigs');
    expect(first.searchParams.get('when')).toBe('all');
    expect(first.searchParams.get('from')).toBeNull();

    await fireEvent.press(screen.getByTestId('my-gigs-more'));
    expect(await screen.findByText('Gig old')).toBeTruthy();
    expect(screen.getByTestId('my-gigs-month-2024-06')).toBeTruthy();
    expect(screen.queryByTestId('my-gigs-more')).toBeNull();
  });

  it('narrows to upcoming or past with the chips', async () => {
    fetchMock.mockImplementation(async () => json({ items: [], nextCursor: null }));
    await render(<MyGigsCalendar />, { wrapper: Providers });
    expect(await screen.findByTestId('my-gigs-empty')).toBeTruthy();

    await fireEvent.press(screen.getByRole('radio', { name: 'Past' }));
    expect(await screen.findByText(/No past gigs yet/)).toBeTruthy();
    expect(requests().at(-1)?.searchParams.get('when')).toBe('past');
  });

  it('the calendar button filters to this month, a day narrows it, and the chip clears back to all time', async () => {
    // This month on the UK calendar, whatever day the test runs.
    const month = monthOf(new Date());
    const first = isoDate(month.year, month.month, 1);
    const second = isoDate(month.year, month.month, 2);
    fetchMock.mockImplementation(async (input) => {
      const url = new URL(String(input));
      if (url.searchParams.get('from')) {
        return json({
          items: [gig('second', `${second}T19:00:00Z`), gig('first', `${first}T19:00:00Z`)],
          nextCursor: null,
        });
      }
      return json({ items: [gig('any', '2025-01-01T19:00:00Z')], nextCursor: null });
    });
    await render(<MyGigsCalendar />, { wrapper: Providers });
    expect(await screen.findByText('Gig any')).toBeTruthy();

    await fireEvent.press(screen.getByTestId('my-gigs-calendar-button'));
    expect(await screen.findByText('Gig second')).toBeTruthy();
    expect(screen.getByText('Gig first')).toBeTruthy();
    const asked = requests().at(-1)!;
    expect(asked.searchParams.get('from')).toBe(monthWindow(month).from);
    expect(asked.searchParams.get('when')).toBe('all');

    await fireEvent.press(screen.getByTestId(`day-${first}`));
    expect(screen.queryByText('Gig second')).toBeNull();
    expect(screen.getByText('Gig first')).toBeTruthy();

    // The chip clears the day, then the month.
    await fireEvent.press(screen.getByRole('radio', { name: formatGigDate(`${first}T12:00:00Z`) }));
    expect(screen.getByText('Gig second')).toBeTruthy();
    await fireEvent.press(screen.getByRole('radio', { name: monthTitle(month) }));
    expect(await screen.findByText('Gig any')).toBeTruthy();
  });
});
