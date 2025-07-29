import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import { getGetGigQueryKey } from '@/api/generated/catalog/catalog';
import type { GigDetail } from '@/api/generated/model';

import { AttendanceButtons, isGigList } from './AttendanceButtons';

const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();

const GIG_ID = '01920000-0000-7000-8000-000000000001';
const DAY = 24 * 3600 * 1000;
// On the gig's day all three statuses are offered; the time-rule tests below move the date.
const detail = {
  gig: {
    id: GIG_ID,
    title: 'A gig',
    myAttendance: null,
    wentUnconfirmed: false,
    startsAt: new Date().toISOString(),
    status: 'SCHEDULED',
  },
  attendanceCounts: { interested: 0, going: 2, went: 0 },
  lineup: [],
  sources: [],
  postsCount: 0,
  privatePostsCount: 0,
} as unknown as GigDetail;

/**
 * Reads the gig from the cache like the real screen does, so cache updates re-render the buttons; keyed
 * with the viewer's position, as the screen keys it once location is allowed.
 */
function Harness({ initial = detail }: { initial?: GigDetail }) {
  const { data } = useQuery<GigDetail>({
    queryKey: getGetGigQueryKey(GIG_ID, { fromLat: 51.51, fromLng: -0.13 }),
    queryFn: () => initial,
    initialData: initial,
    staleTime: Infinity,
  });
  return <AttendanceButtons detail={data} />;
}

async function renderButtons(initial: GigDetail = detail) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
      mutations: { gcTime: Infinity },
    },
  });
  await render(
    <QueryClientProvider client={client}>
      <Harness initial={initial} />
    </QueryClientProvider>,
  );
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

describe('AttendanceButtons', () => {
  it('shows the new state before the server answers, then keeps it', async () => {
    let answer: (response: Response) => void = () => {};
    fetchMock.mockReturnValueOnce(new Promise<Response>((resolve) => (answer = resolve)));
    await renderButtons();
    expect(screen.getByTestId('attendance-counts')).toHaveTextContent(
      '2 going · 0 interested · 0 went',
    );

    await fireEvent.press(screen.getByRole('radio', { name: 'Going' }));

    // Optimistic: selected and counted while the request is still in flight.
    await waitFor(() => expect(screen.getByRole('radio', { name: 'Going' })).toBeChecked());
    expect(screen.getByTestId('attendance-counts')).toHaveTextContent(/^3 going/);
    expect(fetchMock.mock.calls[0]![0]).toBe(
      `http://localhost:8088/api/v1/gigs/${GIG_ID}/attendance`,
    );
    expect(fetchMock.mock.calls[0]![1]?.method).toBe('PUT');
    expect(fetchMock.mock.calls[0]![1]?.body).toBe(JSON.stringify({ status: 'GOING' }));

    answer(json({ myAttendance: 'GOING', attendanceCounts: { interested: 0, going: 3, went: 0 } }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(screen.getByRole('radio', { name: 'Going' })).toBeChecked();
    expect(screen.queryByTestId('attendance-error')).toBeNull();
  });

  it('rolls back and says so when the request fails', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    await renderButtons();

    await fireEvent.press(screen.getByRole('radio', { name: 'Interested' }));

    await waitFor(() => expect(screen.getByTestId('attendance-error')).toBeOnTheScreen());
    expect(screen.getByRole('radio', { name: 'Interested' })).not.toBeChecked();
    expect(screen.getByTestId('attendance-counts')).toHaveTextContent(
      '2 going · 0 interested · 0 went',
    );
  });

  it('pressing the selected status again clears it with a DELETE', async () => {
    fetchMock.mockResolvedValueOnce(
      json({ myAttendance: 'GOING', attendanceCounts: { interested: 0, going: 3, went: 0 } }),
    );
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await renderButtons();

    await fireEvent.press(screen.getByRole('radio', { name: 'Going' }));
    await waitFor(() => expect(screen.getByRole('radio', { name: 'Going' })).toBeChecked());
    await fireEvent.press(screen.getByRole('radio', { name: 'Going' }));

    await waitFor(() => expect(screen.getByRole('radio', { name: 'Going' })).not.toBeChecked());
    expect(screen.getByTestId('attendance-counts')).toHaveTextContent(/^2 going/);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(fetchMock.mock.calls[1]![1]?.method).toBe('DELETE');
  });
});

describe('isGigList (what a new mark refreshes)', () => {
  it('matches gig lists and the stats counted from Went gigs, nothing else', () => {
    expect(isGigList(['/api/v1/gigs', { limit: 20 }])).toBe(true);
    expect(isGigList(['/api/v1/me/gigs'])).toBe(true);
    expect(isGigList(['/api/v1/users/u1/stats', { top: 10 }])).toBe(true);
    expect(isGigList(['/api/v1/users/u1/stats/gigs/count', { artistId: 'a1' }])).toBe(true);
    expect(isGigList(['/api/v1/users/u1/stats/gigs', { artistId: 'a' }])).toBe(true);
    // A profile's Going list shows the viewer's own mark on each gig.
    expect(isGigList(['/api/v1/users/u1/gigs/going', { limit: 20 }])).toBe(true);
    expect(isGigList(['/api/v1/gigs/g1'])).toBe(false);
    expect(isGigList(['/api/v1/users/u1/posts'])).toBe(false);
  });
});

const at = (offsetDays: number, gig: Partial<GigDetail['gig']> = {}, counts = {}) =>
  ({
    ...detail,
    gig: {
      ...detail.gig,
      startsAt: new Date(Date.now() + offsetDays * DAY).toISOString(),
      ...gig,
    },
    attendanceCounts: { interested: 1, going: 2, went: 3, ...counts },
  }) as GigDetail;

describe('AttendanceButtons time rules', () => {
  const offered = () => screen.queryAllByRole('radio').map((chip) => chip.props.accessibilityLabel);

  it('offers Interested and Going before the gig, and counts who is going', async () => {
    await renderButtons(at(3));
    expect(screen.getByRole('radio', { name: 'Interested' })).toBeOnTheScreen();
    expect(screen.getByRole('radio', { name: 'Going' })).toBeOnTheScreen();
    expect(screen.queryByRole('radio', { name: 'Went' })).toBeNull();
    expect(screen.getByTestId('attendance-counts')).toHaveTextContent(/^2 going · 1 interested$/);
  });

  it('offers only Went once the gig is over, and counts only who went', async () => {
    await renderButtons(at(-3));
    expect(screen.getByRole('radio', { name: 'Went' })).toBeOnTheScreen();
    expect(screen.queryByRole('radio', { name: 'Going' })).toBeNull();
    expect(screen.queryByRole('radio', { name: 'Interested' })).toBeNull();
    expect(screen.getByTestId('attendance-counts')).toHaveTextContent(/^3 went$/);
  });

  it('offers nothing on a cancelled gig', async () => {
    await renderButtons(at(3, { status: 'CANCELLED' }));
    expect(offered()).toEqual([]);
  });

  it('asks "Did you go?" about a Going whose gig is over; Yes confirms it as Went', async () => {
    fetchMock.mockResolvedValueOnce(
      json({ myAttendance: 'WENT', attendanceCounts: { interested: 0, going: 0, went: 3 } }),
    );
    await renderButtons(at(-2, { myAttendance: 'WENT', wentUnconfirmed: true }));
    expect(screen.getByRole('radio', { name: 'Went' })).toBeChecked();
    expect(screen.getByTestId('did-you-go')).toBeOnTheScreen();

    await fireEvent.press(screen.getByText('Yes, I went'));

    await waitFor(() => expect(screen.queryByTestId('did-you-go')).toBeNull());
    expect(fetchMock.mock.calls[0]![1]?.method).toBe('PUT');
    expect(fetchMock.mock.calls[0]![1]?.body).toBe(JSON.stringify({ status: 'WENT' }));
    expect(screen.getByRole('radio', { name: 'Went' })).toBeChecked();
  });

  it('tapping an assumed Went confirms it rather than clearing it', async () => {
    fetchMock.mockResolvedValueOnce(
      json({ myAttendance: 'WENT', attendanceCounts: { interested: 0, going: 0, went: 3 } }),
    );
    await renderButtons(at(-2, { myAttendance: 'WENT', wentUnconfirmed: true }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Went' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(fetchMock.mock.calls[0]![1]?.method).toBe('PUT');
  });

  it('No removes the mark', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await renderButtons(at(-2, { myAttendance: 'WENT', wentUnconfirmed: true }));
    await fireEvent.press(screen.getByText('No'));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(fetchMock.mock.calls[0]![1]?.method).toBe('DELETE');
    await waitFor(() => expect(screen.getByRole('radio', { name: 'Went' })).not.toBeChecked());
    expect(screen.getByTestId('attendance-counts')).toHaveTextContent(/^2 went$/);
  });
});
