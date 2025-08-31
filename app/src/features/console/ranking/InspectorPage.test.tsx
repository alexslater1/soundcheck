import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react-native';

import type { FeedProfile, PageServedSession, RankingPreview } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import InspectorPage from './InspectorPage';
import { config, item, post } from './rankingTestData';

let mockParams: { user?: string; tab?: string } = {};
let mockProfile: () => Promise<FeedProfile>;
let mockServed: () => Promise<PageServedSession>;
let mockPreview: () => Promise<RankingPreview>;
const mockConfigs = [config({})];

jest.mock('expo-router', () => ({
  useLocalSearchParams: () => mockParams,
  useRouter: () => ({ setParams: jest.fn() }),
}));
jest.mock('@/api/generated/admin-feed/admin-feed', () => ({
  getAdminFeedProfileQueryKey: (id: string) => ['profile', id],
  getAdminFeedServedQueryKey: (id: string) => ['served', id],
  adminFeedProfile: () => mockProfile(),
  adminFeedServed: () => mockServed(),
}));
jest.mock('@/api/generated/admin-ranking/admin-ranking', () => ({
  useAdminListRankingConfigs: () => ({ status: 'success', data: mockConfigs }),
  adminPreviewRanking: () => mockPreview(),
}));
// The picker has its own debounce and search; here it only needs to be there.
jest.mock('./UserPicker', () => ({ UserPicker: () => null }));

const profile: FeedProfile = {
  user: { id: 'u1', handle: 'maya', displayName: 'Maya' },
  genres: [{ id: 1, name: 'Indie', weight: 0.8, chosen: 1, engaged: 0.2 }],
  artists: [{ id: 'ar1', name: 'The Band', score: 0.6, followed: true }],
  authors: [],
  followingCount: 3,
  followedArtistCount: 1,
};

async function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  await render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <InspectorPage />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('InspectorPage', () => {
  beforeEach(() => {
    mockProfile = async () => profile;
    mockServed = async () => ({ items: [], nextCursor: null });
    mockPreview = async () => ({
      a: { configVersion: 1, items: [item(1, null, 'kit'), item(2, null, 'lou')] },
      candidates: 12,
      commonItems: 0,
    });
  });

  it('asks for a user first', async () => {
    mockParams = {};
    await renderPage();
    expect(screen.getByTestId('inspector-no-user')).toBeOnTheScreen();
  });

  it("shows the user's taste and what they would see now", async () => {
    mockParams = { user: 'u1' };
    await renderPage();
    expect(await screen.findByText('@maya · Maya')).toBeOnTheScreen();
    expect(screen.getByTestId('genre-1')).toBeOnTheScreen();
    expect(screen.getByText('0.60 · The Band · followed')).toBeOnTheScreen();
    expect(screen.getByText('No author affinity yet.')).toBeOnTheScreen();
    expect(await screen.findByText('@kit')).toBeOnTheScreen();
    expect(screen.getByText('@lou')).toBeOnTheScreen();
  });

  it('shows the error state when the user cannot be loaded', async () => {
    mockParams = { user: 'gone' };
    mockProfile = async () => {
      throw new Error('No user gone');
    };
    await renderPage();
    expect(await screen.findByTestId('inspector-error')).toBeOnTheScreen();
  });

  it('lists the sessions they were shown, with outcomes, or says there are none', async () => {
    mockParams = { user: 'u1', tab: 'shown' };
    await renderPage();
    expect(await screen.findByTestId('inspector-shown-empty')).toBeOnTheScreen();
  });

  it('shows a served session item by item', async () => {
    mockParams = { user: 'u1', tab: 'shown' };
    mockServed = async () => ({
      items: [
        {
          sessionId: 's1',
          startedAt: '2026-10-04T10:00:00Z',
          configId: 'c1',
          configVersion: 1,
          shown: 2,
          positives: 1,
          negatives: 0,
          entries: [
            {
              position: 1,
              postId: 'p1',
              post: post('p1', 'kit'),
              score: 0.4,
              exploration: false,
              positive: true,
              negative: false,
              maxWatchFraction: 0.75,
            },
            {
              position: 2,
              postId: 'p2',
              score: 0.3,
              exploration: true,
              positive: false,
              negative: false,
            },
          ],
        },
      ],
      nextCursor: null,
    });
    await renderPage();
    expect(await screen.findByText('2 shown · 1 positive · 0 negative')).toBeOnTheScreen();
    expect(screen.getByText('POSITIVE')).toBeOnTheScreen();
    expect(screen.getByText('watched 75 %')).toBeOnTheScreen();
    expect(screen.getByText('post no longer visible')).toBeOnTheScreen();
    expect(screen.getByText('EXPLORE')).toBeOnTheScreen();
  });
});
