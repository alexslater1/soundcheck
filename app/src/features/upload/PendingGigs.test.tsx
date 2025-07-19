import { fireEvent, render, screen } from '@testing-library/react-native';

import type { GigSummary } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { PendingGigs } from './PendingGigs';

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
  }) as GigSummary;

const mockUnposted = jest.fn();
jest.mock('@/api/generated/posts/posts', () => ({
  useListUnpostedGigs: () => mockUnposted(),
}));
jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

async function show(props: Partial<React.ComponentProps<typeof PendingGigs>> = {}) {
  const onPick = jest.fn();
  const view = await render(
    <ThemeProvider>
      <PendingGigs onPick={onPick} {...props} />
    </ThemeProvider>,
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

  it('is quiet on Create when nothing is pending, and says so on My gigs', async () => {
    mockUnposted.mockReturnValue({ isPending: false, data: [] });
    const { view } = await show();
    expect(screen.queryByTestId('pending-gigs')).toBeNull();
    expect(screen.queryByTestId('pending-empty')).toBeNull();
    await view.unmount();

    await show({ showEmpty: true });
    expect(screen.getByTestId('pending-empty')).toBeTruthy();
  });

  it('offers a retry when the list cannot be loaded', async () => {
    const refetch = jest.fn();
    mockUnposted.mockReturnValue({ isPending: false, isError: true, refetch });
    await show();
    await fireEvent.press(screen.getByText('Try again'));
    expect(refetch).toHaveBeenCalled();
  });
});
