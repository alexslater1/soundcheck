import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react-native';
import type { ReactNode } from 'react';

import type { GigMergePreview } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { gig, preview, tabProps } from './catalogTestData';
import { MergeTab } from './MergeTab';
import type { CatalogTabProps } from './tabTypes';

type PreviewState = {
  data?: GigMergePreview;
  isPending: boolean;
  isError: boolean;
  error?: unknown;
};

let mockPreview: PreviewState;
const mockPreviewCalls: { from: string; into: string; enabled: boolean }[] = [];
const mockMutate = jest.fn();

jest.mock('@/api/generated/admin-catalog/admin-catalog', () => ({
  getAdminListFanAddedGigsQueryKey: () => ['/api/v1/admin/catalog/gigs/fan-added'],
  useAdminPreviewGigMerge: (
    from: string,
    params: { into: string },
    options: { query: { enabled: boolean } },
  ) => {
    mockPreviewCalls.push({ from, into: params.into, enabled: options.query.enabled });
    return { ...mockPreview, refetch: jest.fn() };
  },
  useAdminMergeGig: () => ({
    mutate: mockMutate,
    reset: jest.fn(),
    isPending: false,
    isError: false,
  }),
}));
jest.mock('@/api/generated/admin-users/admin-users', () => ({
  useAdminUsersByIds: () => ({ data: [{ id: 'user-1', handle: 'sam' }] }),
}));
jest.mock('@/api/generated/catalog/catalog', () => ({
  useListGigs: () => ({ data: undefined, isFetching: false, isError: false }),
  useGetGig: () => ({ data: undefined, isFetching: false, isError: false }),
}));
jest.mock('expo-router', () => ({
  Link: ({ children }: { children: ReactNode }) => children,
}));

const pair = { from: gig('gig-from', 'Keo (fan copy)'), into: gig('gig-into', 'Keo'), nonce: 1 };

async function renderTab(withPair = true, props: Partial<CatalogTabProps> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  await render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <MergeTab {...tabProps({ pair: withPair ? pair : null, ...props })} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('MergeTab', () => {
  beforeEach(() => {
    mockPreviewCalls.length = 0;
    mockMutate.mockReset();
    mockPreview = { data: preview(), isPending: false, isError: false };
  });

  it('asks for two gigs before previewing anything', async () => {
    mockPreview = { isPending: true, isError: false };
    await renderTab(false);
    expect(screen.getByTestId('merge-empty')).toBeOnTheScreen();
    expect(mockPreviewCalls.every((c) => !c.enabled)).toBe(true);
  });

  it('shows a skeleton while the preview loads', async () => {
    mockPreview = { isPending: true, isError: false };
    await renderTab();
    expect(screen.getByTestId('merge-preview-loading')).toBeOnTheScreen();
    expect(mockPreviewCalls.at(-1)).toEqual({ from: 'gig-from', into: 'gig-into', enabled: true });
  });

  it("shows the server's reason when the preview fails", async () => {
    mockPreview = {
      isPending: false,
      isError: true,
      error: { problem: { detail: 'Gig gig-from was already merged into gig-x' } },
    };
    await renderTab();
    expect(screen.getByTestId('merge-preview-error')).toBeOnTheScreen();
    expect(screen.getByText('Gig gig-from was already merged into gig-x')).toBeOnTheScreen();
  });

  it('shows both gigs side by side and what will move', async () => {
    await renderTab();
    expect(screen.getByTestId('merge-from')).toBeOnTheScreen();
    expect(screen.getByTestId('merge-into')).toBeOnTheScreen();
    expect(screen.getByText('Added by @sam')).toBeOnTheScreen();
    expect(screen.getByText('TICKETMASTER tm-1 · primary')).toBeOnTheScreen();
    expect(screen.getByText('• 3 fan marks (Interested, Going, Went)')).toBeOnTheScreen();
    expect(screen.getByText('• 1 post')).toBeOnTheScreen();
    expect(screen.queryByText(/^• 0 /)).toBeNull(); // a kind with nothing to move isn't listed
    expect(screen.queryByTestId('merge-conflicts')).toBeNull();
  });

  it('lists the conflicts and refuses to start the merge', async () => {
    mockPreview = {
      data: preview({
        conflicts: [
          '@sam has an open post at both gigs for 2026-10-03: delete or hide one of them first',
        ],
        sameDay: false,
      }),
      isPending: false,
      isError: false,
    };
    await renderTab();
    expect(
      screen.getByText(
        '• @sam has an open post at both gigs for 2026-10-03: delete or hide one of them first',
      ),
    ).toBeOnTheScreen();
    expect(screen.getByText('⚠ The two gigs are on different days.')).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('merge-start'));
    expect(screen.queryByText('Merge these gigs?')).toBeNull();
  });

  it('confirms with the counts that will move, then merges', async () => {
    await renderTab();
    await fireEvent.press(screen.getByTestId('merge-start'));
    expect(screen.getByText('Merge these gigs?')).toBeOnTheScreen();
    expect(
      screen.getByText('• Move 1 post to Keo · Sat 3 Oct 2026 · Band on the Wall'),
    ).toBeOnTheScreen();
    expect(
      screen.getByText('• Delete Keo (fan copy) · Sat 3 Oct 2026 · Band on the Wall'),
    ).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('confirm-ok'));
    expect(mockMutate).toHaveBeenCalledWith(
      { from: 'gig-from', data: { into: 'gig-into' } },
      expect.anything(),
    );
  });

  it("after a merge from a venue merge's list, ticks the pair and offers the way back", async () => {
    const onGigMerged = jest.fn();
    const openTab = jest.fn();
    mockMutate.mockImplementation(
      (_vars, options: { onSuccess: (r: { into: string; moved: [] }) => void }) =>
        options.onSuccess({ into: 'gig-into', moved: [] }),
    );
    await renderTab(true, {
      onGigMerged,
      openTab,
      venueMerge: {
        name: 'The O2',
        result: {
          into: 'v-real',
          moved: [],
          sameNight: [
            {
              movedGigId: 'gig-from',
              movedTitle: 'Keo',
              gigId: 'gig-into',
              title: 'Keo',
              day: '2026-10-03',
            },
            { movedGigId: 'g-c', movedTitle: 'Bea', gigId: 'g-d', title: 'Bea', day: '2026-11-18' },
          ],
        },
      },
      mergedGigIds: new Set(['gig-from']),
    });
    await fireEvent.press(screen.getByTestId('merge-start'));
    await fireEvent.press(screen.getByTestId('confirm-ok'));
    expect(onGigMerged).toHaveBeenCalledWith('gig-from');
    await fireEvent.press(screen.getByText('Back to the same-night list (1 left)'));
    expect(openTab).toHaveBeenCalledWith('venues');
  });
});
