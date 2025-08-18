import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import type { GigDetail } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { canEditGig, EditGigDetails, parseDate } from './EditGigDetails';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();

const detail = (myAttendance: string | null, addedByMe = false, status = 'SCHEDULED') =>
  ({
    gig: { id: 'g1', myAttendance, status, venue: { id: 'v1' }, distanceKm: null },
    lineup: [{ id: 'keo', name: 'KEO', genres: [], isFollowing: false }],
    addedByMe,
  }) as unknown as GigDetail;

async function show(d: GigDetail) {
  await render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ThemeProvider>
        <EditGigDetails detail={d} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(
    new Response(JSON.stringify(detail('GOING')), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    }),
  );
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

describe('EditGigDetails', () => {
  it('is for anyone signed in, whatever their mark, on a gig that is on', () => {
    expect(canEditGig(detail('GOING'))).toBe(true);
    expect(canEditGig(detail('WENT'))).toBe(true);
    expect(canEditGig(detail('INTERESTED'))).toBe(true);
    expect(canEditGig(detail(null))).toBe(true);
    expect(canEditGig(detail('GOING', false, 'CANCELLED'))).toBe(false);
  });

  it('reads a calendar date', () => {
    expect(parseDate('2026-10-02')).toBe('2026-10-02');
    expect(parseDate('2026-02-30')).toBeNull();
    expect(parseDate('2/10/2026')).toBeNull();
  });

  it('fixes the start time', async () => {
    await show(detail('GOING'));
    await fireEvent.press(screen.getByTestId('edit-gig-open'));
    expect(screen.queryByLabelText('Date')).toBeNull();

    await fireEvent.changeText(screen.getByLabelText('Start time'), '21:15');
    await fireEvent.press(screen.getByText('Save time'));

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls.at(-1)!;
    expect(String(url)).toMatch(/\/gigs\/g1$/);
    expect(init?.method).toBe('PATCH');
    expect(init?.body).toBe(JSON.stringify({ startTime: '21:15' }));
    expect(await screen.findByTestId('edit-gig-done')).toHaveTextContent(
      'Start time set to 21:15.',
    );
  });

  it('lets the fan who added the gig change its date', async () => {
    await show(detail('WENT', true));
    await fireEvent.press(screen.getByTestId('edit-gig-open'));
    await fireEvent.changeText(screen.getByLabelText('Date'), '2026-10-02');
    await fireEvent.press(screen.getByText('Save date'));
    await waitFor(() =>
      expect(fetchMock.mock.calls.at(-1)![1]?.body).toBe(JSON.stringify({ date: '2026-10-02' })),
    );
  });

  it('says which act headlined', async () => {
    const twoActs = {
      ...detail('WENT'),
      lineup: [
        { id: 'keo', name: 'KEO', genres: [], isFollowing: false },
        { id: 'tooth', name: 'Tooth', genres: [], isFollowing: false },
      ],
    } as unknown as GigDetail;
    await show(twoActs);
    await fireEvent.press(screen.getByTestId('edit-gig-open'));
    expect(screen.getByText('Who headlined?')).toBeTruthy();
    await fireEvent.press(screen.getByText('Tooth'));
    await waitFor(() =>
      expect(fetchMock.mock.calls.at(-1)![1]?.body).toBe(JSON.stringify({ headlinerId: 'tooth' })),
    );
    expect(await screen.findByTestId('edit-gig-done')).toHaveTextContent(
      'Tooth is now the headliner.',
    );
  });

  it('asks no headliner question when the bill has one act', async () => {
    await show(detail('GOING'));
    await fireEvent.press(screen.getByTestId('edit-gig-open'));
    expect(screen.queryByText('Who headlined?')).toBeNull();
  });

  it('offers the fix to a fan who is only interested', async () => {
    await show(detail('INTERESTED'));
    expect(screen.getByTestId('edit-gig-open')).toBeTruthy();
  });
});
