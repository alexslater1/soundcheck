import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { useState } from 'react';

import type {
  PageSuspectVenue,
  SuspectVenue,
  VenueHit,
  VenueMergePreview,
  VenueMergeResult,
} from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { gig, tabProps } from './catalogTestData';
import type { VenueMergeDone } from './tabTypes';
import { VenuesTab } from './VenuesTab';

const mockMerge = jest.fn();
const mockFix = jest.fn();
const mockOpenLink = jest.fn();
const mockPreviewCalls: { from: string; into: string; enabled: boolean }[] = [];
let mockSuspects: () => Promise<PageSuspectVenue>;
const mockHits: VenueHit[] = [
  { id: 'v-dup', name: 'The O2 Arena', city: 'London' },
  { id: 'v-real', name: 'The O2', city: 'London' },
];
const mockPreview: VenueMergePreview = {
  from: {
    id: 'v-dup',
    name: 'The O2 Arena',
    externalIds: ['TICKETMASTER:KovZ1'],
    counts: [{ what: 'gigs', count: 12 }],
  },
  into: {
    id: 'v-real',
    name: 'The O2',
    externalIds: ['TICKETMASTER:KovZ2'],
    counts: [{ what: 'gigs', count: 300 }],
  },
  distanceMetres: 30,
  moving: [
    { what: 'gigs', count: 12 },
    { what: 'followers', count: 2 },
  ],
  sameNight: [
    { movedGigId: 'g-a', movedTitle: 'Keo', gigId: 'g-b', title: 'Keo', day: '2026-10-03' },
  ],
  conflicts: [],
};

jest.mock('@/api/generated/admin-catalog/admin-catalog', () => ({
  getAdminListSuspectVenuesQueryKey: () => ['/api/v1/admin/catalog/venues'],
  adminListSuspectVenues: () => mockSuspects(),
  useAdminFixVenueLocation: () => ({
    mutate: mockFix,
    reset: jest.fn(),
    isPending: false,
    isError: false,
  }),
  useAdminPreviewVenueMerge: (
    from: string,
    params: { into: string },
    options: { query: { enabled: boolean } },
  ) => {
    mockPreviewCalls.push({ from, into: params.into, enabled: options.query.enabled });
    return {
      data: options.query.enabled ? mockPreview : undefined,
      isPending: !options.query.enabled,
      isError: false,
      refetch: jest.fn(),
    };
  },
  useAdminMergeVenue: () => ({
    mutate: mockMerge,
    reset: jest.fn(),
    isPending: false,
    isError: false,
  }),
}));
jest.mock('@/api/generated/catalog/catalog', () => ({
  useSearchVenues: (params: { q: string }) => ({
    data: mockHits.filter((h) => h.name.includes(params.q)),
    isFetching: false,
    isError: false,
  }),
  useGetVenue: () => ({ data: undefined, isFetching: false, isError: false }),
  getGig: async (id: string) => ({
    gig: { ...jest.requireActual('./catalogTestData').gig(id, 'Keo') },
  }),
}));
jest.mock('@/api/generated/admin-users/admin-users', () => ({
  useAdminUsersByIds: () => ({ data: [] }),
}));
jest.mock('@/lib/useDebounced', () => ({ useDebounced: <T,>(value: T) => value }));
jest.mock('@/lib/openLink', () => ({ openWebLink: (url: string) => mockOpenLink(url) }));

function suspect(id: string, overrides: Partial<SuspectVenue> = {}): SuspectVenue {
  return {
    id,
    name: `Back Room ${id}`,
    city: 'Leeds',
    reasons: ['FAN_ADDED', 'CITY_CENTRE'],
    gigs: 3,
    createdAt: '2026-10-04T10:00:00Z',
    location: { lat: 53.7997, lng: -1.5492 },
    osmUrl:
      'https://www.openstreetmap.org/?mlat=53.799700&mlon=-1.549200#map=18/53.799700/-1.549200',
    ...overrides,
  };
}

const mockOpenMerge = jest.fn();

/** The tab with the Catalog page's state for it: the last venue merge lives there, not in the tab. */
function Harness({ merged }: { merged: ReadonlySet<string> }) {
  const [venueMerge, setVenueMerge] = useState<VenueMergeDone | null>(null);
  return (
    <VenuesTab
      {...tabProps({ openMerge: mockOpenMerge, venueMerge, setVenueMerge, mergedGigIds: merged })}
    />
  );
}

async function renderTab(merged: ReadonlySet<string> = new Set()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  await render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <Harness merged={merged} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

async function pickBoth() {
  await fireEvent.changeText(screen.getByTestId('venue-merge-from-search'), 'O2 Arena');
  await fireEvent.press(screen.getByTestId('venue-merge-from-result-v-dup'));
  await fireEvent.changeText(screen.getByTestId('venue-merge-into-search'), 'The O2');
  await fireEvent.press(screen.getByTestId('venue-merge-into-result-v-real'));
}

describe('VenuesTab', () => {
  beforeEach(() => {
    mockMerge.mockReset();
    mockFix.mockReset();
    mockOpenLink.mockReset();
    mockOpenMerge.mockReset();
    mockPreviewCalls.length = 0;
    mockSuspects = async () => ({ items: [suspect('v1')], nextCursor: null });
  });

  it('previews a venue merge from two picked venues and merges after confirming', async () => {
    await renderTab();
    await pickBoth();
    expect(mockPreviewCalls.at(-1)).toEqual({ from: 'v-dup', into: 'v-real', enabled: true });
    expect(screen.getByTestId('venue-merge-preview')).toBeOnTheScreen();
    expect(screen.getByText('• 12 gigs')).toBeOnTheScreen();
    expect(screen.getByText('TICKETMASTER:KovZ1')).toBeOnTheScreen();
    expect(screen.getAllByTestId('venue-merge-warning')).toHaveLength(1); // the shared night, not 30 m

    await fireEvent.press(screen.getByTestId('venue-merge-start'));
    expect(screen.getByText('• Move 2 followers to The O2')).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('confirm-ok'));
    expect(mockMerge).toHaveBeenCalledWith(
      { from: 'v-dup', data: { into: 'v-real' } },
      expect.anything(),
    );
  });

  it('after a merge, a shared night opens the Merge tab on that pair', async () => {
    const result: VenueMergeResult = {
      into: 'v-real',
      moved: mockPreview.moving,
      sameNight: mockPreview.sameNight,
    };
    mockMerge.mockImplementation((_vars, options: { onSuccess: (r: VenueMergeResult) => void }) =>
      options.onSuccess(result),
    );
    await renderTab();
    await pickBoth();
    await fireEvent.press(screen.getByTestId('venue-merge-start'));
    await fireEvent.press(screen.getByTestId('confirm-ok'));
    expect(screen.getByText('Merged into The O2. Moved 12 gigs, 2 followers.')).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('venue-merge-pair-g-a'));
    await screen.findByTestId('venue-merge-done');
    expect(mockOpenMerge).toHaveBeenCalledWith(gig('g-a', 'Keo'), gig('g-b', 'Keo'));
  });

  it('ticks a same-night pair that was merged since', async () => {
    const result: VenueMergeResult = {
      into: 'v-real',
      moved: mockPreview.moving,
      sameNight: mockPreview.sameNight,
    };
    mockMerge.mockImplementation((_vars, options: { onSuccess: (r: VenueMergeResult) => void }) =>
      options.onSuccess(result),
    );
    await renderTab(new Set(['g-a']));
    await pickBoth();
    await fireEvent.press(screen.getByTestId('venue-merge-start'));
    await fireEvent.press(screen.getByTestId('confirm-ok'));
    expect(screen.getByTestId('venue-merge-pair-done-g-a')).toHaveTextContent('Merged ✓');
    expect(screen.queryByTestId('venue-merge-pair-g-a')).toBeNull();
  });

  it('lists suspect venues and moves one to a checked point', async () => {
    await renderTab();
    expect(await screen.findByText("Added by a fan · At a town's centre point")).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('venue-fix-v1'));
    const input = screen.getByTestId('venue-form-v1-input');

    await fireEvent.changeText(input, '-1.5492, 53.7997');
    expect(
      screen.getByText('That point is outside the UK. Latitude comes first.'),
    ).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('venue-form-v1-save'));
    expect(mockFix).not.toHaveBeenCalled();

    await fireEvent.changeText(input, '53.8008, -1.5491');
    await fireEvent.press(screen.getByText('Check the point on OpenStreetMap ↗'));
    expect(mockOpenLink).toHaveBeenCalledWith(
      'https://www.openstreetmap.org/?mlat=53.800800&mlon=-1.549100#map=18/53.800800/-1.549100',
    );
    await fireEvent.press(screen.getByTestId('venue-form-v1-save'));
    expect(mockFix).toHaveBeenCalledWith({ id: 'v1', data: { lat: 53.8008, lng: -1.5491 } });
  });

  it('says when no venue looks misplaced', async () => {
    mockSuspects = async () => ({ items: [], nextCursor: null });
    await renderTab();
    expect(await screen.findByText('No suspect venues')).toBeOnTheScreen();
  });
});
