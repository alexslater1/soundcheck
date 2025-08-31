import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen } from '@testing-library/react-native';

import type { RankingConfigView, RankingPreview } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import RankingPage from './RankingPage';
import { config, item } from './rankingTestData';

let mockParams: { user?: string; handle?: string; config?: string } = {};
let mockList: { status: string; data?: RankingConfigView[]; refetch?: () => void };
const mockActivate = jest.fn(async (id: string) => ({ id }));
const mockCreate = jest.fn(async (_body: unknown) => ({ id: 'new' }));
const mockPreview = (): RankingPreview => ({
  a: { configVersion: 1, items: [item(1, 2, 'kit'), item(2, 1, 'lou')] },
  b: { configVersion: 0, items: [item(1, 2, 'lou'), item(2, 1, 'kit')] },
  overlapAt10: 1,
  kendallTau: -1,
  commonItems: 2,
  candidates: 2,
});

jest.mock('expo-router', () => ({
  useLocalSearchParams: () => mockParams,
  useRouter: () => ({ setParams: jest.fn() }),
}));
jest.mock('@/api/generated/admin-ranking/admin-ranking', () => ({
  useAdminListRankingConfigs: () => mockList,
  getAdminListRankingConfigsQueryKey: () => ['configs'],
  adminPreviewRanking: async () => mockPreview(),
  adminActivateRankingConfig: (id: string) => mockActivate(id),
  adminCreateRankingConfig: (body: unknown) => mockCreate(body),
  adminUpdateRankingConfig: jest.fn(),
}));
jest.mock('./UserPicker', () => ({ UserPicker: () => null }));
jest.mock('./ReplayPanel', () => ({ ReplayPanel: () => null }));

const active = config({ id: 'c1', version: 1, status: 'ACTIVE' });
const draft = config({
  id: 'c2',
  version: 2,
  status: 'DRAFT',
  source: 'LAB',
  weights: { ...active.weights, recency: 0 },
  params: { ...active.params, authorCapPerSession: 3 },
  metrics: { auc: 0.7123, report: 'lab/reports/r2.html' },
  activatedAt: null,
});
const old = config({
  id: 'c0',
  version: 0,
  status: 'ARCHIVED',
  activatedAt: '2026-09-01T00:00:00Z',
});

async function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  await render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <RankingPage />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('RankingPage', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    mockActivate.mockClear();
    mockCreate.mockClear();
    mockList = { status: 'success', data: [draft, active, old] };
  });
  afterEach(() => jest.useRealTimers());

  it('shows the loading and the error states', async () => {
    mockList = { status: 'pending' };
    await renderPage();
    expect(screen.getByTestId('ranking-loading')).toBeOnTheScreen();
  });

  it('offers a retry when the configs cannot be loaded', async () => {
    mockList = { status: 'error', refetch: jest.fn() };
    await renderPage();
    expect(screen.getByTestId('ranking-error')).toBeOnTheScreen();
  });

  it('asks for a user before it previews, and lists the configs with a lab draft summed up', async () => {
    mockParams = {};
    await renderPage();
    expect(screen.getByTestId('ranking-no-user')).toBeOnTheScreen();
    expect(screen.getByTestId('config-v2')).toHaveTextContent(
      /auc 0\.712 · lab\/reports\/r2\.html/,
    );
    expect(screen.getByTestId('slider-position_prior-locked')).toBeOnTheScreen();
  });

  it('previews both lists with the rank moves and the agreement figures', async () => {
    mockParams = { user: 'u1', handle: 'maya', config: 'c2' };
    await renderPage();
    await act(async () => jest.advanceTimersByTime(400));
    expect(await screen.findByTestId('preview-b-change-1')).toHaveTextContent('▲1');
    expect(screen.getByTestId('stat-tau')).toHaveTextContent('-1.000');
    expect(screen.getByTestId('stat-overlap')).toHaveTextContent('1.00');
  });

  it('activating a draft confirms every change against the active config first', async () => {
    mockParams = { user: 'u1', config: 'c2' };
    await renderPage();

    await fireEvent.press(screen.getByTestId('ranking-activate'));
    expect(screen.getByText('• Version 2 becomes the active ranking config')).toBeOnTheScreen();
    expect(screen.getByText('• Version 1 is archived (it can be re-activated)')).toBeOnTheScreen();
    expect(screen.getByText('• weight recency: 1.5 → 0')).toBeOnTheScreen();
    expect(screen.getByText('• authorCapPerSession: 5 → 3')).toBeOnTheScreen();
    expect(mockActivate).not.toHaveBeenCalled();

    await fireEvent.press(screen.getByTestId('confirm-ok'));
    expect(mockActivate).toHaveBeenCalledWith('c2');
  });

  it('an edited config must be saved before it can be activated, and Re-activate goes back', async () => {
    mockParams = { user: 'u1', config: 'c2' };
    await renderPage();

    await fireEvent.changeText(screen.getByTestId('slider-seen-input'), '-1');
    expect(screen.getByTestId('ranking-activate')).toBeDisabled();
    expect(screen.getByText('Save the edits as a draft before activating them.')).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('ranking-reset'));
    expect(screen.getByTestId('ranking-activate')).toBeEnabled();

    await fireEvent.press(screen.getByTestId('ranking-restore'));
    expect(screen.getByText('Re-activate v0?')).toBeOnTheScreen();
  });

  it('saves edits of the active config as a new draft', async () => {
    mockParams = { user: 'u1', config: 'c1' };
    await renderPage();
    await fireEvent.changeText(screen.getByTestId('slider-recency-input'), '0.5');
    await fireEvent.press(screen.getByTestId('ranking-save'));
    expect(mockCreate).toHaveBeenCalledWith(
      expect.objectContaining({ weights: expect.objectContaining({ recency: 0.5 }) }),
    );
  });
});
