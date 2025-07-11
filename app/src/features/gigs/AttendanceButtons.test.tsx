import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import { getGetGigQueryKey } from '@/api/generated/catalog/catalog';
import type { GigDetail } from '@/api/generated/model';

import { AttendanceButtons, isGigList } from './AttendanceButtons';

const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();

const GIG_ID = '01920000-0000-7000-8000-000000000001';
const detail = {
  gig: { id: GIG_ID, title: 'A gig', myAttendance: null },
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
function Harness() {
  const { data } = useQuery<GigDetail>({
    queryKey: getGetGigQueryKey(GIG_ID, { fromLat: 51.51, fromLng: -0.13 }),
    queryFn: () => detail,
    initialData: detail,
    staleTime: Infinity,
  });
  return <AttendanceButtons detail={data} />;
}

async function renderButtons() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
      mutations: { gcTime: Infinity },
    },
  });
  await render(
    <QueryClientProvider client={client}>
      <Harness />
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
