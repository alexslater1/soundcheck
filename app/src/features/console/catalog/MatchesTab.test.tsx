import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react-native';
import type { ReactNode } from 'react';

import type {
  AdminListMatchReviewsParams,
  MatchReview,
  PageMatchReview,
} from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { gig } from './catalogTestData';
import { MatchesTab, percent } from './MatchesTab';

let mockAnswer: (params: AdminListMatchReviewsParams) => Promise<PageMatchReview>;
const mockCalls: AdminListMatchReviewsParams[] = [];
const mockDecide = jest.fn();
let mockDecideError: unknown = null;

jest.mock('@/api/generated/admin-ingestion/admin-ingestion', () => ({
  getAdminListMatchReviewsQueryKey: (params?: AdminListMatchReviewsParams) => [
    '/api/v1/admin/ingestion/match-reviews',
    ...(params ? [params] : []),
  ],
  adminListMatchReviews: (params: AdminListMatchReviewsParams) => {
    mockCalls.push(params);
    return mockAnswer(params);
  },
  useAdminDecideMatchReview: () => ({
    mutate: mockDecide,
    reset: jest.fn(),
    isPending: false,
    isError: mockDecideError !== null,
    error: mockDecideError,
  }),
}));
jest.mock('expo-router', () => ({
  Link: ({ children }: { children: ReactNode }) => children,
}));

function review(id: string, overrides: Partial<MatchReview> = {}): MatchReview {
  return {
    id,
    createdAt: '2026-10-04T05:10:00Z',
    source: 'VENUE',
    externalId: 'lantern:ev-pk',
    status: 'OPEN',
    gig: gig('gig-new', 'Pale Kites'),
    candidate: gig('gig-old', 'Pale Kite'),
    title: 'Pale Kites',
    candidateTitle: 'Pale Kite',
    titleSimilarity: 0.83,
    actSimilarity: 0.71,
    reason: 'similar title, no shared act',
    ...overrides,
  };
}

async function renderTab() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  await render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <MatchesTab />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('MatchesTab', () => {
  beforeEach(() => {
    mockCalls.length = 0;
    mockDecide.mockReset();
    mockDecideError = null;
  });

  it('formats similarities as percentages', () => {
    expect(percent(0.834)).toBe('83 %');
  });

  it('lists the open questions with both gigs and why the resolver asked', async () => {
    mockAnswer = async () => ({ items: [review('r1')], nextCursor: null });
    await renderTab();
    expect(await screen.findByText('Titles 83 % · acts 71 %')).toBeOnTheScreen();
    expect(screen.getByText('similar title, no shared act')).toBeOnTheScreen();
    expect(screen.getByText('VENUE lantern:ev-pk')).toBeOnTheScreen();
    expect(screen.getByText('Pale Kite · Sat 3 Oct 2026 · Band on the Wall')).toBeOnTheScreen();
    expect(mockCalls.at(-1)).toEqual({ status: 'OPEN' });
  });

  it('keeps a pair apart straight away, and merges only after confirming', async () => {
    mockAnswer = async () => ({ items: [review('r1')], nextCursor: null });
    await renderTab();
    await fireEvent.press(await screen.findByTestId('match-keep-r1'));
    expect(mockDecide).toHaveBeenCalledWith({ id: 'r1', data: { decision: 'KEEP_APART' } });

    await fireEvent.press(screen.getByTestId('match-merge-r1'));
    expect(screen.getByText('Merge these gigs?')).toBeOnTheScreen();
    expect(mockDecide).toHaveBeenCalledTimes(1);
    await fireEvent.press(screen.getByTestId('confirm-ok'));
    expect(mockDecide).toHaveBeenLastCalledWith({ id: 'r1', data: { decision: 'MERGE' } });
  });

  it('shows decided reviews without actions, and an empty queue says so', async () => {
    mockAnswer = async (params) =>
      params.status === 'OPEN'
        ? { items: [], nextCursor: null }
        : {
            items: [review('r2', { status: 'MERGED', decidedAt: '2026-10-05T01:00:00Z' })],
            nextCursor: null,
          };
    await renderTab();
    expect(await screen.findByText('No listings to decide')).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('matches-MERGED'));
    expect(await screen.findByText('2026-10-05')).toBeOnTheScreen();
    expect(screen.queryByTestId('match-merge-r2')).toBeNull();
  });

  it("shows the server's refusal", async () => {
    mockDecideError = {
      problem: { detail: 'The merge would break: @sam has a post at both gigs' },
    };
    mockAnswer = async () => ({ items: [review('r1')], nextCursor: null });
    await renderTab();
    expect(
      await screen.findByText('The merge would break: @sam has a post at both gigs'),
    ).toBeOnTheScreen();
  });
});
