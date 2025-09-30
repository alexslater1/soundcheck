import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react-native';

import type { AnalyticsContent, AnalyticsFeed, AnalyticsOverview } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import OverviewPage, { feedCharts, hadActivity } from './OverviewPage';

let mockOverview: () => Promise<AnalyticsOverview>;
let mockFeed: () => Promise<AnalyticsFeed>;
let mockContent: () => Promise<AnalyticsContent>;
const mockContentParams: unknown[] = [];

jest.mock('expo-router', () => ({
  useLocalSearchParams: () => ({ range: '7d' }),
  useRouter: () => ({ setParams: jest.fn() }),
}));
jest.mock('@/api/generated/admin-analytics/admin-analytics', () => ({
  getAdminAnalyticsOverviewQueryKey: (p: unknown) => ['overview', p],
  getAdminAnalyticsFeedQueryKey: (p: unknown) => ['feed', p],
  getAdminAnalyticsContentQueryKey: (p: unknown) => ['content', p],
  adminAnalyticsOverview: () => mockOverview(),
  adminAnalyticsFeed: () => mockFeed(),
  adminAnalyticsContent: (params: unknown) => {
    mockContentParams.push(params);
    return mockContent();
  },
}));
jest.mock('../charts/ChartCanvas', () => ({ ChartCanvas: () => null }));

const kpi = (value: number | null, previous: number | null) => ({ value, previous });

function overview(share: number | null, events = 9): AnalyticsOverview {
  return {
    from: '2026-09-28T00:00:00Z',
    to: '2026-10-05T00:00:00Z',
    previousFrom: '2026-09-21T00:00:00Z',
    days: 7,
    kpis: {
      dau: kpi(10.857, 0),
      wau: kpi(74, 60),
      newUsers: kpi(12, 10),
      postsPerDay: kpi(80.14, 40),
      uploadsPerDay: kpi(155.4, 155.4),
      processingSuccess: kpi(0.9963, null),
      timeToReadyP50Ms: kpi(31_102.5, 20_000),
      timeToReadyP95Ms: kpi(95_655, null),
      feedEngagement: kpi(0.0479, 0.06),
    },
    series: [
      {
        day: '2026-10-02',
        dauReal: 39,
        dauSynthetic: 31,
        wau: 70,
        newUsers: 12,
        posts: 224,
        uploads: 351,
        processingSuccess: 0.99,
        timeToReadyP50Ms: 30_000,
        served: 4493,
        positive: 505,
      },
    ],
    syntheticShare: {
      events,
      syntheticEvents: 6,
      posts: 0,
      syntheticPosts: 0,
      share,
    },
    syntheticDomains: ['seed.soundcheck.test', 'sim.soundcheck.test'],
  };
}

const rate = (served: number, positive: number) => ({
  served,
  positive,
  rate: served ? positive / served : null,
});

const feed: AnalyticsFeed = {
  from: '2026-09-28T00:00:00Z',
  to: '2026-10-05T00:00:00Z',
  overall: rate(8, 3),
  byPosition: [
    { position: 0, ...rate(2, 1) },
    { position: 1, ...rate(2, 1) },
  ],
  byConfig: [{ version: 3, activatedAt: '2026-10-01T23:30:00Z', ...rate(8, 3) }],
  exploration: rate(2, 1),
  exploitation: rate(6, 2),
  watchHistogram: Array.from({ length: 10 }, (_, i) => ({
    from: i / 10,
    to: (i + 1) / 10,
    count: i === 9 ? 4 : 0,
  })),
  watchByClipLength: [
    {
      label: '< 15 s',
      fromSeconds: 0,
      toSeconds: 15,
      watched: 2,
      meanWatchFraction: 0.75,
      completionRate: 0.5,
    },
  ],
  photo: rate(2, 1),
  video: rate(6, 2),
};

const content: AnalyticsContent = {
  from: '2026-09-28T00:00:00Z',
  to: '2026-10-05T00:00:00Z',
  topArtists: [{ artistId: 'a1', name: 'Pulp', posts: 3, positive: 14 }],
  topGigs: [
    {
      gigId: 'g1',
      title: 'Pulp at the Hall',
      startsAt: '2026-10-02T19:00:00Z',
      venue: 'The Hall',
      posts: 3,
      positive: 14,
    },
  ],
  uploadsByHour: Array.from({ length: 24 }, (_, hour) => ({ hour, uploads: hour === 12 ? 5 : 0 })),
};

async function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  await render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <OverviewPage />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('OverviewPage', () => {
  beforeEach(() => {
    mockOverview = async () => overview(0.7264);
    mockFeed = async () => feed;
    mockContent = async () => content;
  });

  it('shows a skeleton while the overview loads', async () => {
    mockOverview = () => new Promise(() => {});
    mockFeed = () => new Promise(() => {});
    mockContent = () => new Promise(() => {});
    await renderPage();
    expect(screen.getByTestId('overview-loading')).toBeOnTheScreen();
  });

  it('shows the eight tiles, the synthetic banner, the charts and the top tables', async () => {
    await renderPage();
    expect(await screen.findByTestId('overview-synthetic-banner')).toBeOnTheScreen();
    expect(
      screen.getByText("73 % of this period's activity is from seeded or simulated accounts"),
    ).toBeOnTheScreen();
    for (const id of [
      'kpi-dau',
      'kpi-wau',
      'kpi-new-users',
      'kpi-posts',
      'kpi-uploads',
      'kpi-success',
      'kpi-ready',
      'kpi-engagement',
    ]) {
      expect(screen.getByTestId(id)).toBeOnTheScreen();
    }
    expect(screen.getByText('10.9')).toBeOnTheScreen(); // DAU
    expect(screen.getByText('31.1 s · 1.6 min')).toBeOnTheScreen(); // p50 · p95
    expect(screen.getByText('▲ +23.3 % vs previous period')).toBeOnTheScreen(); // WAU 74 on 60
    expect(screen.getByTestId('chart-dau')).toBeOnTheScreen();
    expect(await screen.findByTestId('chart-position')).toBeOnTheScreen();
    expect(screen.getByTestId('chart-config')).toBeOnTheScreen();
    expect(screen.getByTestId('chart-length')).toBeOnTheScreen();
    expect(screen.getByTestId('chart-kind')).toBeOnTheScreen();
    expect(await screen.findByText('Pulp')).toBeOnTheScreen();
    expect(screen.getByText('Pulp at the Hall')).toBeOnTheScreen();
    expect(screen.getByTestId('chart-hours')).toBeOnTheScreen();
    expect(screen.queryByTestId('overview-empty')).toBeNull();
  });

  it('asks for the top 50 artists and gigs and scrolls them past ten rows', async () => {
    await renderPage();
    expect(await screen.findByTestId('top-artists-scroll')).toBeOnTheScreen();
    expect(screen.getByTestId('top-gigs-scroll')).toBeOnTheScreen();
    expect(mockContentParams.at(-1)).toMatchObject({ top: 50 });
  });

  it('says so when nothing happened in the period', async () => {
    mockOverview = async () => ({
      ...overview(null, 0),
      series: [],
    });
    mockFeed = async () => ({ ...feed, overall: rate(0, 0) });
    mockContent = async () => ({ ...content, topArtists: [], topGigs: [] });
    await renderPage();
    expect(await screen.findByTestId('overview-empty')).toBeOnTheScreen();
    expect(screen.getByText(/No activity in this period/)).toBeOnTheScreen();
    expect(await screen.findByTestId('overview-feed-empty')).toBeOnTheScreen();
    expect(
      await screen.findByText('No artist was posted or engaged with in this period'),
    ).toBeOnTheScreen();
  });

  it('shows an error with a retry when the overview fails, and still loads the rest', async () => {
    mockOverview = async () => {
      throw new Error('down');
    };
    await renderPage();
    expect(await screen.findByTestId('overview-error')).toBeOnTheScreen();
    expect(screen.getByText('Try again')).toBeOnTheScreen();
    expect(await screen.findByTestId('chart-position')).toBeOnTheScreen();
    expect(await screen.findByText('Pulp')).toBeOnTheScreen();
  });

  it('shows the feed section its own error', async () => {
    mockFeed = async () => {
      throw new Error('down');
    };
    await renderPage();
    expect(await screen.findByTestId('overview-feed-error')).toBeOnTheScreen();
    expect(await screen.findByTestId('kpi-dau')).toBeOnTheScreen();
    expect(await screen.findByText('Pulp')).toBeOnTheScreen();
  });
});

describe('feedCharts and hadActivity', () => {
  it('turns rates into percent and labels configs and positions for people', () => {
    const charts = feedCharts(feed);
    const position = charts.find((c) => c.key === 'position')?.spec;
    expect(position).toMatchObject({ kind: 'line', x: ['1', '2'], unit: '%' });
    expect(position?.kind === 'line' ? position.series[0]?.values : []).toEqual([50, 50]);
    const config = charts.find((c) => c.key === 'config')?.spec;
    expect(config?.kind === 'bar' ? config.categories : []).toEqual(['v3 · 2 Oct']);
    const watch = charts.find((c) => c.key === 'watch')?.spec;
    expect(watch?.kind === 'histogram' ? watch.bins[9] : null).toEqual({
      from: 90,
      to: 100,
      count: 4,
    });
  });

  it('counts a period with only served items as activity', () => {
    const quiet = { ...overview(null, 0), series: [] };
    expect(hadActivity(quiet)).toBe(false);
    expect(hadActivity(overview(0.5))).toBe(true);
  });
});
