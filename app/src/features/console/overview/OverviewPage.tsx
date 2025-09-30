import { useQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import {
  adminAnalyticsContent,
  adminAnalyticsFeed,
  adminAnalyticsOverview,
  getAdminAnalyticsContentQueryKey,
  getAdminAnalyticsFeedQueryKey,
  getAdminAnalyticsOverviewQueryKey,
} from '@/api/generated/admin-analytics/admin-analytics';
import type {
  AnalyticsContent,
  AnalyticsFeed,
  AnalyticsOverview,
  AnalyticsTopArtist,
  AnalyticsTopGig,
} from '@/api/generated/model';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { Chart } from '../charts/Chart';
import type { ChartSpec } from '../charts/chartTypes';
import { DataTable, type Column } from '../DataTable';
import { rangeQuery } from '../dateRange';
import { KpiTile } from '../KpiTile';
import { useConsoleRange } from '../useConsoleRange';
import {
  configLabel,
  dayLabel,
  formatCount,
  formatMs,
  formatPercent,
  signedDelta,
  syntheticBanner,
} from './format';

/**
 * The console's landing page: eight KPIs against the previous period, the share of the activity
 * that seeded and simulated accounts produced (always shown), the daily charts, For You engagement
 * and the top artists and gigs. Each of the three endpoints loads and fails on its own.
 */
/** How many top artists and gigs the Overview asks for (the API's maximum). */
export const TOP_LISTED = 50;

export default function OverviewPage() {
  const [range] = useConsoleRange();
  const params = rangeQuery(range);
  const overview = useQuery({
    queryKey: getAdminAnalyticsOverviewQueryKey(params),
    queryFn: ({ signal }) => adminAnalyticsOverview(params, { signal }),
  });
  const feed = useQuery({
    queryKey: getAdminAnalyticsFeedQueryKey(params),
    queryFn: ({ signal }) => adminAnalyticsFeed(params, { signal }),
  });
  // The top lists ask for 50; about ten rows show and the rest scroll.
  const contentParams = { ...params, top: TOP_LISTED };
  const content = useQuery({
    queryKey: getAdminAnalyticsContentQueryKey(contentParams),
    queryFn: ({ signal }) => adminAnalyticsContent(contentParams, { signal }),
  });

  return (
    <View style={styles.page} testID="overview-page">
      <Text variant="display">Overview</Text>
      {overview.status === 'error' ? (
        <StateMessage
          testID="overview-error"
          title="Couldn't load the overview"
          detail="The server didn't answer. The period is kept; try again."
          onRetry={() => void overview.refetch()}
        />
      ) : overview.status === 'pending' ? (
        <OverviewSkeleton />
      ) : (
        <Headline overview={overview.data} />
      )}
      <FeedSection status={feed.status} data={feed.data} onRetry={() => void feed.refetch()} />
      <ContentSection
        status={content.status}
        data={content.data}
        onRetry={() => void content.refetch()}
      />
    </View>
  );
}

function OverviewSkeleton() {
  return (
    <View testID="overview-loading" style={styles.page}>
      <Skeleton height={56} />
      <View style={styles.tiles}>
        {Array.from({ length: 8 }, (_, i) => (
          <KpiTile key={i} label="Loading" value="" loading />
        ))}
      </View>
      <Skeleton height={240} />
    </View>
  );
}

/** Whether anything at all happened in the period (events, posts, uploads or served items). */
export function hadActivity(o: AnalyticsOverview): boolean {
  return (
    o.syntheticShare.events + o.syntheticShare.posts > 0 ||
    o.series.some((d) => d.uploads > 0 || d.served > 0 || d.newUsers > 0)
  );
}

function Headline({ overview }: { overview: AnalyticsOverview }) {
  const { colors } = useTheme();
  const { kpis, series, syntheticShare } = overview;
  const days = series.map((d) => dayLabel(d.day));
  const defined = (values: (number | null | undefined)[]) =>
    values.filter((v): v is number => typeof v === 'number');
  return (
    <View style={styles.page}>
      <View
        testID="overview-synthetic-banner"
        accessibilityRole="summary"
        style={[styles.banner, { backgroundColor: colors.accent2Soft, borderColor: colors.border }]}
      >
        <Text style={styles.bannerText}>{syntheticBanner(syntheticShare.share)}</Text>
        <Text variant="caption" muted>
          {`Their ${formatCount(syntheticShare.syntheticEvents)} of ${formatCount(syntheticShare.events)} events and ${formatCount(syntheticShare.syntheticPosts)} of ${formatCount(syntheticShare.posts)} posts (accounts at ${overview.syntheticDomains.join(', ') || 'no configured domain'}). The seeded likes and watches are synthetic too, so the feed charts mostly show the seed's behaviour, not people's.`}
        </Text>
      </View>
      {hadActivity(overview) ? null : (
        <StateMessage
          testID="overview-empty"
          title="Nothing happened in this period"
          detail="No events, posts, uploads or served items. Try a longer range."
        />
      )}
      <View style={styles.tiles}>
        <KpiTile
          testID="kpi-dau"
          label="Daily active users"
          value={formatCount(kpis.dau.value)}
          delta={signedDelta(kpis.dau.value, kpis.dau.previous)}
          trend={series.map((d) => d.dauReal + d.dauSynthetic)}
        />
        <KpiTile
          testID="kpi-wau"
          label="Weekly active users"
          value={formatCount(kpis.wau.value)}
          delta={signedDelta(kpis.wau.value, kpis.wau.previous)}
          trend={series.map((d) => d.wau)}
        />
        <KpiTile
          testID="kpi-new-users"
          label="New users"
          value={formatCount(kpis.newUsers.value)}
          delta={signedDelta(kpis.newUsers.value, kpis.newUsers.previous)}
          trend={series.map((d) => d.newUsers)}
        />
        <KpiTile
          testID="kpi-posts"
          label="Posts per day"
          value={formatCount(kpis.postsPerDay.value)}
          delta={signedDelta(kpis.postsPerDay.value, kpis.postsPerDay.previous)}
          trend={series.map((d) => d.posts)}
        />
        <KpiTile
          testID="kpi-uploads"
          label="Uploads per day"
          value={formatCount(kpis.uploadsPerDay.value)}
          delta={signedDelta(kpis.uploadsPerDay.value, kpis.uploadsPerDay.previous)}
          trend={series.map((d) => d.uploads)}
        />
        <KpiTile
          testID="kpi-success"
          label="Processing success"
          value={formatPercent(kpis.processingSuccess.value)}
          delta={signedDelta(kpis.processingSuccess.value, kpis.processingSuccess.previous)}
          trend={defined(series.map((d) => d.processingSuccess))}
        />
        <KpiTile
          testID="kpi-ready"
          label="Time to ready p50 · p95"
          value={`${formatMs(kpis.timeToReadyP50Ms.value)} · ${formatMs(kpis.timeToReadyP95Ms.value)}`}
          delta={signedDelta(kpis.timeToReadyP50Ms.value, kpis.timeToReadyP50Ms.previous)}
          goodWhen="down"
          trend={defined(series.map((d) => d.timeToReadyP50Ms))}
        />
        <KpiTile
          testID="kpi-engagement"
          label="Feed engagement"
          value={formatPercent(kpis.feedEngagement.value)}
          delta={signedDelta(kpis.feedEngagement.value, kpis.feedEngagement.previous)}
          trend={series.filter((d) => d.served > 0).map((d) => d.positive / d.served)}
        />
      </View>
      <View style={styles.grid}>
        <Cell>
          <Chart
            testID="chart-dau"
            title="Daily active users: real vs synthetic"
            spec={{
              kind: 'line',
              x: days,
              series: [
                { key: 'real', label: 'Real', values: series.map((d) => d.dauReal) },
                { key: 'synthetic', label: 'Synthetic', values: series.map((d) => d.dauSynthetic) },
              ],
            }}
          />
        </Cell>
        <Cell>
          <Chart
            testID="chart-posts-uploads"
            title="Posts and uploads per day"
            spec={{
              kind: 'bar',
              categories: days,
              series: [
                { key: 'posts', label: 'Posts', values: series.map((d) => d.posts) },
                { key: 'uploads', label: 'Uploads', values: series.map((d) => d.uploads) },
              ],
            }}
          />
        </Cell>
      </View>
    </View>
  );
}

/** A fraction as a chart value in percent (charts show "40" with the unit "%"); null as 0. */
const pct = (fraction: number | null | undefined) =>
  fraction === null || fraction === undefined ? 0 : Math.round(fraction * 1000) / 10;

/** The For You charts' specs, from the feed endpoint (exported for the tests). */
export function feedCharts(f: AnalyticsFeed): { key: string; title: string; spec: ChartSpec }[] {
  return [
    {
      key: 'position',
      title: 'Engagement by position (position bias)',
      spec: {
        kind: 'line',
        x: f.byPosition.map((p) => String(p.position + 1)),
        series: [
          { key: 'rate', label: 'Positive rate', values: f.byPosition.map((p) => pct(p.rate)) },
        ],
        unit: '%',
      },
    },
    {
      key: 'config',
      title: 'Engagement by ranking config',
      spec: {
        kind: 'bar',
        categories: f.byConfig.map((c) => configLabel(c.version, c.activatedAt)),
        series: [
          { key: 'rate', label: 'Positive rate', values: f.byConfig.map((c) => pct(c.rate)) },
        ],
        unit: '%',
      },
    },
    {
      key: 'exploration',
      title: 'Exploration vs ranked slots',
      spec: {
        kind: 'bar',
        categories: ['Exploration', 'Ranked'],
        series: [
          {
            key: 'rate',
            label: 'Positive rate',
            values: [pct(f.exploration.rate), pct(f.exploitation.rate)],
          },
        ],
        unit: '%',
      },
    },
    {
      key: 'watch',
      title: 'How far watched items were watched',
      spec: {
        kind: 'histogram',
        bins: f.watchHistogram.map((b) => ({
          from: Math.round(b.from * 100),
          to: Math.round(b.to * 100),
          count: b.count,
        })),
        unit: '%',
      },
    },
    {
      key: 'length',
      title: "Watch by the first clip's length",
      spec: {
        kind: 'bar',
        categories: f.watchByClipLength.map((w) => w.label),
        series: [
          {
            key: 'mean',
            label: 'Mean watch',
            values: f.watchByClipLength.map((w) => pct(w.meanWatchFraction)),
          },
          {
            key: 'complete',
            label: 'Watched to the end',
            values: f.watchByClipLength.map((w) => pct(w.completionRate)),
          },
        ],
        unit: '%',
      },
    },
    {
      key: 'kind',
      title: 'Photo vs video posts',
      spec: {
        kind: 'bar',
        categories: [`Photo (${f.photo.served} served)`, `Video (${f.video.served} served)`],
        series: [
          { key: 'rate', label: 'Positive rate', values: [pct(f.photo.rate), pct(f.video.rate)] },
        ],
        unit: '%',
      },
    },
  ];
}

function FeedSection({
  status,
  data,
  onRetry,
}: {
  status: 'pending' | 'error' | 'success';
  data: AnalyticsFeed | undefined;
  onRetry: () => void;
}) {
  return (
    <View style={styles.page} testID="overview-feed">
      <Text variant="title">For You engagement</Text>
      {status === 'error' ? (
        <StateMessage
          testID="overview-feed-error"
          title="Couldn't load feed engagement"
          onRetry={onRetry}
        />
      ) : status === 'pending' || !data ? (
        <Skeleton height={240} />
      ) : data.overall.served === 0 ? (
        <StateMessage
          testID="overview-feed-empty"
          title="No For You items were served in this period"
          detail="Engagement is measured on served items (the Following feed logs none)."
        />
      ) : (
        <>
          <Text muted>
            {`${formatPercent(data.overall.rate)} of ${formatCount(data.overall.served)} served items were engaged with (a like, comment, share, tap, or three quarters watched; a hide or report never counts).`}
          </Text>
          <View style={styles.grid}>
            {feedCharts(data).map((c) => (
              <Cell key={c.key}>
                <Chart testID={`chart-${c.key}`} title={c.title} spec={c.spec} />
              </Cell>
            ))}
          </View>
        </>
      )}
    </View>
  );
}

const artistColumns: Column<AnalyticsTopArtist>[] = [
  {
    key: 'name',
    title: 'Artist',
    flex: 3,
    sortValue: (a) => a.name,
    render: (a) => <Text>{a.name}</Text>,
  },
  {
    key: 'positive',
    title: 'Engaged',
    align: 'right',
    sortValue: (a) => a.positive,
    render: (a) => <Text>{formatCount(a.positive)}</Text>,
  },
  {
    key: 'posts',
    title: 'Posts',
    align: 'right',
    sortValue: (a) => a.posts,
    render: (a) => <Text>{formatCount(a.posts)}</Text>,
  },
];

const gigColumns: Column<AnalyticsTopGig>[] = [
  {
    key: 'title',
    title: 'Gig',
    flex: 3,
    sortValue: (g) => g.title,
    render: (g) => (
      <View>
        <Text numberOfLines={1}>{g.title}</Text>
        <Text variant="caption" muted numberOfLines={1}>
          {`${g.venue} · ${dayLabel(g.startsAt.slice(0, 10))}`}
        </Text>
      </View>
    ),
  },
  {
    key: 'positive',
    title: 'Engaged',
    align: 'right',
    sortValue: (g) => g.positive,
    render: (g) => <Text>{formatCount(g.positive)}</Text>,
  },
  {
    key: 'posts',
    title: 'Posts',
    align: 'right',
    sortValue: (g) => g.posts,
    render: (g) => <Text>{formatCount(g.posts)}</Text>,
  },
];

function ContentSection({
  status,
  data,
  onRetry,
}: {
  status: 'pending' | 'error' | 'success';
  data: AnalyticsContent | undefined;
  onRetry: () => void;
}) {
  return (
    <View style={styles.page} testID="overview-content">
      <Text variant="title">Content</Text>
      <View style={styles.grid}>
        <Cell>
          <Text style={styles.tableTitle}>Top artists</Text>
          <ScrollView style={styles.topList} nestedScrollEnabled testID="top-artists-scroll">
            <DataTable
              testID="top-artists"
              columns={artistColumns}
              rows={data?.topArtists}
              rowKey={(a) => a.artistId}
              status={status}
              onRetry={onRetry}
              emptyTitle="No artist was posted or engaged with in this period"
            />
          </ScrollView>
        </Cell>
        <Cell>
          <Text style={styles.tableTitle}>Top gigs</Text>
          <ScrollView style={styles.topList} nestedScrollEnabled testID="top-gigs-scroll">
            <DataTable
              testID="top-gigs"
              columns={gigColumns}
              rows={data?.topGigs}
              rowKey={(g) => g.gigId}
              status={status}
              onRetry={onRetry}
              emptyTitle="No gig was posted or engaged with in this period"
            />
          </ScrollView>
        </Cell>
      </View>
      {data ? (
        <Chart
          testID="chart-hours"
          title="Uploads by hour of day (UK time)"
          spec={{
            kind: 'bar',
            categories: data.uploadsByHour.map((h) => String(h.hour).padStart(2, '0')),
            series: [
              {
                key: 'uploads',
                label: 'Uploads',
                values: data.uploadsByHour.map((h) => h.uploads),
              },
            ],
          }}
        />
      ) : null}
    </View>
  );
}

function Cell({ children }: { children: ReactNode }) {
  return <View style={styles.cell}>{children}</View>;
}

const styles = StyleSheet.create({
  page: { gap: spacing.md },
  banner: { borderWidth: 1, borderRadius: radius.md, padding: spacing.md, gap: spacing.xs },
  bannerText: { fontWeight: '700' },
  tiles: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md },
  cell: { flexGrow: 1, flexBasis: 460, minWidth: 0, gap: spacing.xs },
  tableTitle: { fontWeight: '600' },
  // About ten rows of a top list; the other 40 scroll.
  topList: { maxHeight: 480 },
});
