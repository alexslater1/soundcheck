import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

import type { GigDetail } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { ActsSeen, seenActs } from './ActsSeen';

const fetchMock = jest.fn<Promise<Response>, [RequestInfo | URL, RequestInit | undefined]>();

const act = (id: string, name: string) => ({ id, name, genres: [], isFollowing: false });
const detail = (myAttendance: string | null, missed: string[] = []) =>
  ({
    gig: { id: 'g1', myAttendance, myActsMissed: missed, wentUnconfirmed: false },
    lineup: [act('keo', 'KEO'), act('tooth', 'Tooth')],
    attendanceCounts: { interested: 0, going: 0, went: 1 },
  }) as unknown as GigDetail;

async function show(d: GigDetail) {
  await render(
    <QueryClientProvider client={new QueryClient()}>
      <ThemeProvider>
        <ActsSeen detail={d} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

describe('ActsSeen', () => {
  it('counts every act but the ones missed', () => {
    expect(seenActs(detail('WENT'))).toEqual(['keo', 'tooth']);
    expect(seenActs(detail('WENT', ['tooth']))).toEqual(['keo']);
  });

  it('shows only for a Went on a gig with more than one act', async () => {
    await show(detail('GOING'));
    expect(screen.queryByTestId('acts-seen')).toBeNull();
  });

  it('starts with the whole bill ticked; unticking one sends the acts seen', async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ seen: ['keo'], missed: ['tooth'] }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
    );
    await show(detail('WENT'));
    expect(screen.getByRole('radio', { name: 'KEO' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Tooth' })).toBeChecked();

    await fireEvent.press(screen.getByRole('radio', { name: 'Tooth' }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(String(fetchMock.mock.calls[0]![0])).toMatch(/\/gigs\/g1\/attendance\/acts$/);
    expect(fetchMock.mock.calls[0]![1]?.method).toBe('PUT');
    expect(fetchMock.mock.calls[0]![1]?.body).toBe(JSON.stringify({ artistIds: ['keo'] }));
  });

  it('keeps at least one act ticked', async () => {
    await show(detail('WENT', ['tooth']));
    await fireEvent.press(screen.getByRole('radio', { name: 'KEO' }));
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
