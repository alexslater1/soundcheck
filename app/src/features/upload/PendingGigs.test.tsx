import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react-native';

import type { GigSummary } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { PendingGigs, useMarkPendingGigsSeen, usePendingGigCount } from './PendingGigs';
import { usePendingSeen } from './pendingSeen';

const gig = (id: string): GigSummary =>
  ({
    id,
    title: `Gig ${id}`,
    startsAt: '2026-09-20T19:30:00Z',
    status: 'SCHEDULED',
    venue: { id: 'v', name: 'Venue' },
    headliner: { id: 'h', name: 'Act', genres: [], isFollowing: false },
    support: [],
    userAdded: false,
    wentUnconfirmed: false,
    myActsMissed: [],
  }) as GigSummary;

const mockUnposted = jest.fn();
jest.mock('@/api/generated/posts/posts', () => ({
  useListUnpostedGigs: () => mockUnposted(),
  getListUnpostedGigsQueryKey: () => ['/api/v1/me/gigs/unposted'],
}));
const mockSet = jest.fn();
const mockClear = jest.fn();
jest.mock('@/api/generated/catalog/catalog', () => ({
  getGetGigQueryKey: (id: string) => [`/api/v1/gigs/${id}`],
  useSetGigAttendance: () => ({ mutateAsync: mockSet, isPending: false }),
  useClearGigAttendance: () => ({ mutateAsync: mockClear, isPending: false }),
}));
jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
const mockFocused = jest.fn(() => true);
jest.mock('@react-navigation/native', () => ({ useIsFocused: () => mockFocused() }));

beforeEach(() => {
  mockFocused.mockReturnValue(true);
  usePendingSeen.setState({ seen: {} });
});

async function show(props: Partial<React.ComponentProps<typeof PendingGigs>> = {}) {
  const onPick = jest.fn();
  const view = await render(
    <QueryClientProvider client={new QueryClient()}>
      <ThemeProvider>
        <PendingGigs onPick={onPick} {...props} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
  return { onPick, view };
}

describe('PendingGigs', () => {
  beforeEach(() => jest.clearAllMocks());

  it('lists the gigs without clips, three at first, and picks one', async () => {
    mockUnposted.mockReturnValue({ isPending: false, data: ['1', '2', '3', '4'].map(gig) });
    const { onPick } = await show();

    expect(screen.queryByLabelText('Gig 4 · Venue')).toBeNull();
    await fireEvent.press(screen.getByText('Show all 4'));
    await fireEvent.press(screen.getByLabelText('Gig 4 · Venue'));

    expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ id: '4' }));
  });

  it('asks "Did you go?" about a Going whose gig is over', async () => {
    mockUnposted.mockReturnValue({
      isPending: false,
      data: [{ ...gig('1'), myAttendance: 'WENT', wentUnconfirmed: true }, gig('2')],
    });
    mockSet.mockResolvedValue({});
    mockClear.mockResolvedValue(undefined);
    await show();

    expect(screen.getAllByTestId('did-you-go')).toHaveLength(1);
    await fireEvent.press(screen.getByText('Yes, I went'));
    await waitFor(() =>
      expect(mockSet).toHaveBeenCalledWith({ id: '1', data: { status: 'WENT' } }),
    );
    await fireEvent.press(screen.getByText('No'));
    await waitFor(() => expect(mockClear).toHaveBeenCalledWith({ id: '1' }));
  });

  it('is quiet when nothing is pending', async () => {
    mockUnposted.mockReturnValue({ isPending: false, data: [] });
    await show();
    expect(screen.queryByTestId('pending-gigs')).toBeNull();
  });

  it('counts the pending gigs for the Create tab badge, and 0 while loading', async () => {
    mockUnposted.mockReturnValue({ isPending: true });
    expect((await renderHook(() => usePendingGigCount('me'))).result.current).toBe(0);
    mockUnposted.mockReturnValue({ isPending: false, data: [{ id: 'a' }, { id: 'b' }] });
    expect((await renderHook(() => usePendingGigCount('me'))).result.current).toBe(2);
  });

  it('clears the badge once Create has been open, and counts a gig pending after that', async () => {
    mockUnposted.mockReturnValue({ isPending: false, data: [{ id: 'a' }, { id: 'b' }] });
    await renderHook(() => useMarkPendingGigsSeen('me'));
    expect((await renderHook(() => usePendingGigCount('me'))).result.current).toBe(0);
    // Another account on the same phone still sees its own badge.
    expect((await renderHook(() => usePendingGigCount('other'))).result.current).toBe(2);

    mockUnposted.mockReturnValue({ isPending: false, data: [{ id: 'c' }, { id: 'a' }, { id: 'b' }] });
    expect((await renderHook(() => usePendingGigCount('me'))).result.current).toBe(1);
  });

  it('does not mark anything seen while the tab is not in view', async () => {
    mockFocused.mockReturnValue(false);
    mockUnposted.mockReturnValue({ isPending: false, data: [{ id: 'a' }] });
    await renderHook(() => useMarkPendingGigsSeen('me'));
    expect((await renderHook(() => usePendingGigCount('me'))).result.current).toBe(1);
  });

  it('offers a retry when the list cannot be loaded', async () => {
    const refetch = jest.fn();
    mockUnposted.mockReturnValue({ isPending: false, isError: true, refetch });
    await show();
    await fireEvent.press(screen.getByText('Try again'));
    expect(refetch).toHaveBeenCalled();
  });
});
