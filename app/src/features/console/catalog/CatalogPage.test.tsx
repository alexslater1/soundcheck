import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react-native';
import type { ReactNode } from 'react';

import type { AdminListFanAddedGigsParams, PageFanAddedGig } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import CatalogPage from './CatalogPage';
import { fanGig, preview } from './catalogTestData';

let mockAnswer: (params: AdminListFanAddedGigsParams) => Promise<PageFanAddedGig>;
const mockCalls: AdminListFanAddedGigsParams[] = [];
const mockPreviewCalls: { from: string; into: string; enabled: boolean }[] = [];
const mockReview = jest.fn();
const mockDelete = jest.fn();
const mockPreviewData = preview();

jest.mock('@/api/generated/admin-catalog/admin-catalog', () => ({
  getAdminListFanAddedGigsQueryKey: (params?: AdminListFanAddedGigsParams) => [
    '/api/v1/admin/catalog/gigs/fan-added',
    ...(params ? [params] : []),
  ],
  adminListFanAddedGigs: (params: AdminListFanAddedGigsParams) => {
    mockCalls.push(params);
    return mockAnswer(params);
  },
  useAdminReviewFanAddedGig: () => ({ mutate: mockReview, isPending: false, isError: false }),
  useAdminDeleteGig: () => ({
    mutate: mockDelete,
    reset: jest.fn(),
    isPending: false,
    isError: false,
  }),
  useAdminPreviewGigMerge: (
    from: string,
    params: { into: string },
    options: { query: { enabled: boolean } },
  ) => {
    mockPreviewCalls.push({ from, into: params.into, enabled: options.query.enabled });
    return { data: mockPreviewData, isPending: false, isError: false, refetch: jest.fn() };
  },
  useAdminMergeGig: () => ({
    mutate: jest.fn(),
    reset: jest.fn(),
    isPending: false,
    isError: false,
  }),
}));
jest.mock('@/api/generated/admin-ingestion/admin-ingestion', () => ({
  getAdminListMatchReviewsQueryKey: () => ['/api/v1/admin/ingestion/match-reviews'],
  adminListMatchReviews: async () => ({ items: [], nextCursor: null }),
  useAdminDecideMatchReview: () => ({
    mutate: jest.fn(),
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

async function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  await render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <CatalogPage />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('CatalogPage', () => {
  beforeEach(() => {
    mockCalls.length = 0;
    mockPreviewCalls.length = 0;
    mockReview.mockReset();
    mockDelete.mockReset();
  });

  it('lists fan-added gigs with who added them, their counts and likely duplicates', async () => {
    mockAnswer = async () => ({ items: [fanGig('g1')], nextCursor: null });
    await renderPage();
    expect(await screen.findByText('@sam')).toBeOnTheScreen();
    expect(screen.getByText('Fan gig g1 · Sat 3 Oct 2026 · Band on the Wall')).toBeOnTheScreen();
    expect(screen.getByText('Keo · Sat 3 Oct 2026 · Band on the Wall')).toBeOnTheScreen();
    expect(mockCalls[0]).toEqual({ reviewed: false });
  });

  it('shows the empty and error states', async () => {
    mockAnswer = async () => ({ items: [], nextCursor: null });
    await renderPage();
    expect(await screen.findByText('No fan-added gigs to review')).toBeOnTheScreen();

    mockAnswer = async () => {
      throw new Error('down');
    };
    await fireEvent.press(screen.getByTestId('fan-gigs-reviewed'));
    expect(await screen.findByTestId('fan-gigs-table-error')).toBeOnTheScreen();
    expect(mockCalls.at(-1)).toEqual({ reviewed: true });
  });

  it('reviews a gig, and deletes one only after confirming', async () => {
    mockAnswer = async () => ({ items: [fanGig('g1')], nextCursor: null });
    await renderPage();
    await fireEvent.press(await screen.findByTestId('fan-gig-review-g1'));
    expect(mockReview).toHaveBeenCalledWith({ id: 'g1' });

    await fireEvent.press(screen.getByTestId('fan-gig-delete-g1'));
    expect(mockDelete).not.toHaveBeenCalled();
    expect(screen.getByText('• Remove 2 fan marks, its bill and listings')).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('confirm-ok'));
    expect(mockDelete).toHaveBeenCalledWith({ id: 'g1' });
  });

  it('"Merge into…" opens the Merge tab on that pair', async () => {
    mockAnswer = async () => ({ items: [fanGig('g1')], nextCursor: null });
    await renderPage();
    await fireEvent.press(await screen.findByTestId('fan-gig-merge-g1-gig-real'));
    expect(screen.getByTestId('catalog-merge')).toBeOnTheScreen();
    expect(mockPreviewCalls.at(-1)).toEqual({ from: 'g1', into: 'gig-real', enabled: true });
    expect(screen.getByTestId('merge-preview')).toBeOnTheScreen();
  });

  it('opens the match reviews on their own tab', async () => {
    mockAnswer = async () => ({ items: [], nextCursor: null });
    await renderPage();
    await fireEvent.press(screen.getByTestId('catalog-tab-matches'));
    expect(screen.getByTestId('catalog-matches')).toBeOnTheScreen();
    expect(await screen.findByText('No listings to decide')).toBeOnTheScreen();
  });
});
