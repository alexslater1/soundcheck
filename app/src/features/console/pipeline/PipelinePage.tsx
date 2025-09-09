import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import {
  adminPipeline,
  adminPipelineFail,
  adminPipelineRetry,
  getAdminPipelineQueryKey,
} from '@/api/generated/admin-pipeline/admin-pipeline';
import type {
  PipelineAsset,
  PipelineJob,
  PipelineOverview,
  PipelineStage,
} from '@/api/generated/model';
import { ApiError } from '@/api/http';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { Chart } from '../charts/Chart';
import { chartPalette } from '../charts/palette';
import { ConfirmDialog } from '../ConfirmDialog';
import { DataTable, type Column } from '../DataTable';
import { KpiTile } from '../KpiTile';
import { useConsoleRange } from '../useConsoleRange';
import {
  attentionReason,
  BAR_STAGES,
  barScale,
  dailyTranscodeChart,
  failChanges,
  failuresChart,
  formatMs,
  POLL_MS,
  retryChanges,
  shortId,
  STAGE_LABELS,
  stageSegments,
  updatedAgo,
} from './pipelineModel';

const QUEUE_UNREACHABLE =
  "The queue couldn't be reached, so its counts are blank. The database figures below are current.";

type Pending = { action: 'retry' | 'fail'; asset: PipelineAsset };

/** Explains the attempt column: a manual retry starts the count again. */
export const ATTEMPT_HINT =
  'A manual retry gives the clip a fresh set of attempts, so its history can show "attempt 1" twice.';

/**
 * The console's media pipeline page: the transcode queue and its dead-letter queue, assets by
 * status, the clips that need an operator (stranded or failed) with Retry and Fail, stage
 * timings, failures by reason and the daily transcode p50/p95. Tiles refresh every 5 s while the
 * tab is visible.
 */
export default function PipelinePage() {
  const { colors } = useTheme();
  const [range] = useConsoleRange();
  const params = { from: range.from.toISOString(), to: range.to.toISOString() };
  const query = useQuery({
    queryKey: getAdminPipelineQueryKey(params),
    queryFn: ({ signal }) => adminPipeline(params, { signal }),
    refetchInterval: POLL_MS,
  });
  const now = useNow(1000);
  const data = query.data;

  return (
    <View style={styles.page}>
      <View style={styles.header}>
        <Text variant="display">Media pipeline</Text>
        <Text variant="caption" muted testID="pipeline-updated">
          {updatedAgo(query.dataUpdatedAt, now) ?? ''}
        </Text>
      </View>
      {query.status === 'error' && !data ? (
        <StateMessage
          testID="pipeline-error"
          title="Couldn't load the pipeline"
          detail="The server didn't answer. It is asked again every 5 seconds."
          onRetry={() => void query.refetch()}
        />
      ) : (
        <>
          <Tiles data={data} />
          {data?.queueError ? (
            <View
              testID="pipeline-queue-error"
              style={[styles.banner, { borderColor: colors.warning }]}
            >
              <Text>{QUEUE_UNREACHABLE}</Text>
            </View>
          ) : null}
          <Attention data={data} status={query.status} onRetry={() => void query.refetch()} />
          <StagesTable stages={data?.stages} />
          {data ? (
            <View style={styles.charts}>
              <View style={styles.chart}>
                {data.failuresByReason.length ? (
                  <Chart
                    title="Failed attempts by reason"
                    spec={failuresChart(data.failuresByReason)}
                    testID="pipeline-failures-chart"
                  />
                ) : (
                  <StateMessage
                    testID="pipeline-failures-empty"
                    title="No failed attempts in this period"
                  />
                )}
              </View>
              <View style={styles.chart}>
                {data.dailyTranscode.length ? (
                  <Chart
                    title="Transcode time per day (p50, p95)"
                    spec={dailyTranscodeChart(data.dailyTranscode)}
                    testID="pipeline-daily-chart"
                  />
                ) : (
                  <StateMessage
                    testID="pipeline-daily-empty"
                    title="No clips transcoded in this period"
                  />
                )}
              </View>
            </View>
          ) : null}
          <RecentJobs
            jobs={data?.recentJobs}
            status={query.status}
            onRetry={() => void query.refetch()}
          />
        </>
      )}
    </View>
  );
}

/** The current time, ticking, so "updated n s ago" counts up between refreshes. */
function useNow(everyMs: number): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), everyMs);
    return () => clearInterval(t);
  }, [everyMs]);
  return now;
}

function Tiles({ data }: { data: PipelineOverview | undefined }) {
  const loading = !data;
  const count = (status: string) => data?.statuses.find((s) => s.status === status)?.count ?? 0;
  const queueValue = (n: number | undefined) => (n === undefined ? '—' : String(n));
  const stranded = data?.attention.filter((a) => a.stranded).length ?? 0;
  return (
    <View style={styles.tiles} testID="pipeline-tiles">
      <KpiTile
        label="Queued"
        value={queueValue(data?.queue?.visible)}
        loading={loading}
        testID="tile-queued"
      />
      <KpiTile
        label="In flight"
        value={queueValue(data?.queue?.inFlight)}
        loading={loading}
        testID="tile-in-flight"
      />
      <KpiTile
        label="Dead letters"
        value={queueValue(data?.dlq?.visible)}
        loading={loading}
        testID="tile-dlq"
      />
      <KpiTile
        label="Processing"
        value={String(count('PROCESSING'))}
        loading={loading}
        testID="tile-processing"
      />
      <KpiTile label="Stranded" value={String(stranded)} loading={loading} testID="tile-stranded" />
      <KpiTile
        label="Failed"
        value={String(count('FAILED'))}
        loading={loading}
        testID="tile-failed"
      />
    </View>
  );
}

function Attention({
  data,
  status,
  onRetry,
}: {
  data: PipelineOverview | undefined;
  status: 'pending' | 'error' | 'success';
  onRetry: () => void;
}) {
  const { colors } = useTheme();
  const queryClient = useQueryClient();
  const [pending, setPending] = useState<Pending | null>(null);
  const mutation = useMutation({
    mutationFn: (p: Pending) =>
      p.action === 'retry' ? adminPipelineRetry(p.asset.id) : adminPipelineFail(p.asset.id),
    onSuccess: () => {
      setPending(null);
      void queryClient.invalidateQueries({ queryKey: getAdminPipelineQueryKey() });
    },
  });
  const rule = data?.strandedRule ?? { uploadedMinutes: 10, leaseMinutes: 15 };

  const columns: Column<PipelineAsset>[] = [
    {
      key: 'id',
      title: 'Clip',
      render: (a) => (
        <Text variant="caption" selectable>
          …{shortId(a.id)} · {a.kind.toLowerCase()}
        </Text>
      ),
    },
    {
      key: 'status',
      title: 'Status',
      sortValue: (a) => a.status,
      render: (a) => (
        <Text variant="caption" style={{ color: a.stranded ? colors.warning : colors.danger }}>
          {a.stranded ? `${a.status} · stranded` : a.status}
        </Text>
      ),
    },
    {
      key: 'reason',
      title: 'Why',
      flex: 3,
      render: (a) => (
        <Text variant="caption" numberOfLines={2}>
          {attentionReason(a, rule)}
        </Text>
      ),
    },
    {
      key: 'attempts',
      title: 'Attempts',
      align: 'right',
      sortValue: (a) => a.attempts,
      render: (a) => <Text variant="caption">{a.attempts}</Text>,
    },
    {
      key: 'since',
      title: 'Since',
      sortValue: (a) => a.since,
      render: (a) => <Text variant="caption">{a.since.slice(0, 16).replace('T', ' ')}</Text>,
    },
    {
      key: 'actions',
      title: '',
      flex: 2,
      render: (a) => (
        <View style={styles.actions}>
          <Button
            title="Retry"
            size="small"
            variant="outline"
            onPress={() => {
              mutation.reset();
              setPending({ action: 'retry', asset: a });
            }}
            testID={`retry-${a.id}`}
          />
          {a.stranded ? (
            <Button
              title="Fail"
              size="small"
              variant="danger"
              onPress={() => {
                mutation.reset();
                setPending({ action: 'fail', asset: a });
              }}
              testID={`fail-${a.id}`}
            />
          ) : null}
        </View>
      ),
    },
  ];

  return (
    <View style={styles.section}>
      <Text variant="title">Needs attention</Text>
      <Text variant="caption" muted>
        Stranded: uploaded over {rule.uploadedMinutes} min ago with nothing working on it, or
        processing with no heartbeat for over {rule.leaseMinutes} min. Failed: out of attempts.
      </Text>
      <DataTable
        testID="pipeline-attention"
        columns={columns}
        rows={data?.attention}
        rowKey={(a) => a.id}
        status={data ? 'success' : status}
        onRetry={onRetry}
        emptyTitle="Nothing needs attention"
        emptyDetail="No clip is stranded or failed."
      />
      <ConfirmDialog
        visible={pending !== null}
        title={
          pending?.action === 'fail'
            ? `Fail clip …${shortId(pending.asset.id)}?`
            : `Retry clip …${pending ? shortId(pending.asset.id) : ''}?`
        }
        changes={
          pending
            ? pending.action === 'retry'
              ? retryChanges(pending.asset)
              : failChanges(pending.asset, rule)
            : []
        }
        confirmLabel={pending?.action === 'fail' ? 'Fail clip' : 'Retry clip'}
        destructive={pending?.action === 'fail'}
        busy={mutation.isPending}
        error={mutation.error ? actionError(mutation.error) : null}
        onConfirm={() => pending && mutation.mutate(pending)}
        onCancel={() => setPending(null)}
      />
    </View>
  );
}

/** The server's reason for a refusal, in words (it changed since the page last refreshed, usually). */
export function actionError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'MEDIA_REJECTED') return 'The file itself is bad: retrying would not help.';
    if (error.code === 'MEDIA_NOT_RETRYABLE' || error.code === 'MEDIA_NOT_STRANDED') {
      return `Not any more: ${error.message}. The list refreshes in a moment.`;
    }
    return error.message;
  }
  return 'Something went wrong. Try again.';
}

function StagesTable({ stages }: { stages: PipelineStage[] | undefined }) {
  const { colors } = useTheme();
  return (
    <View style={styles.section} testID="pipeline-stages">
      <Text variant="title">Stage times in this period</Text>
      {!stages ? (
        <Skeleton height={60} />
      ) : (
        <View style={[styles.stageRow, { borderColor: colors.border }]}>
          {stages.map((s) => (
            <View key={s.stage} style={styles.stageCell} testID={`stage-${s.stage}`}>
              <Text variant="label" muted>
                {(STAGE_LABELS[s.stage] ?? s.stage).toUpperCase()}
              </Text>
              <Text>p50 {formatMs(s.p50Ms)}</Text>
              <Text variant="caption" muted>
                p95 {formatMs(s.p95Ms)} · {s.count} attempts
              </Text>
            </View>
          ))}
        </View>
      )}
    </View>
  );
}

function RecentJobs({
  jobs,
  status,
  onRetry,
}: {
  jobs: PipelineJob[] | undefined;
  status: 'pending' | 'error' | 'success';
  onRetry: () => void;
}) {
  const { colors, scheme } = useTheme();
  const palette = chartPalette(colors, scheme).series;
  const [hint, setHint] = useState(false);
  const scale = useMemo(() => barScale(jobs ?? []), [jobs]);
  const stageColor = (stage: string) =>
    palette[BAR_STAGES.indexOf(stage as (typeof BAR_STAGES)[number]) % palette.length];

  const columns: Column<PipelineJob>[] = [
    {
      key: 'clip',
      title: 'Clip',
      render: (j) => <Text variant="caption">…{shortId(j.mediaId)}</Text>,
    },
    {
      key: 'attempt',
      title: 'Attempt',
      align: 'right',
      sortValue: (j) => j.attempt,
      render: (j) => <Text variant="caption">{j.attempt}</Text>,
    },
    {
      key: 'status',
      title: 'Status',
      sortValue: (j) => j.status,
      render: (j) => (
        <Text
          variant="caption"
          numberOfLines={1}
          style={{
            color:
              j.status === 'SUCCEEDED'
                ? colors.success
                : j.status === 'RUNNING' || j.status === 'RELEASED'
                  ? colors.text
                  : colors.danger,
          }}
        >
          {j.status}
        </Text>
      ),
    },
    {
      key: 'queueWait',
      title: 'Queue wait',
      align: 'right',
      sortValue: (j) => j.queueWaitMs,
      render: (j) => <Text variant="caption">{formatMs(j.queueWaitMs)}</Text>,
    },
    {
      key: 'stages',
      title: 'Stages',
      flex: 4,
      render: (j) => (
        <View
          style={[styles.bar, { backgroundColor: colors.surfaceRaised }]}
          accessibilityLabel={stageSegments(j, scale)
            .map((s) => `${STAGE_LABELS[s.stage]} ${formatMs(s.ms)}`)
            .join(', ')}
          testID={`bar-${j.id}`}
        >
          {stageSegments(j, scale).map((s) => (
            <View
              key={s.stage}
              testID={`bar-${j.id}-${s.stage}`}
              style={{ width: `${s.percent}%`, backgroundColor: stageColor(s.stage) }}
            />
          ))}
        </View>
      ),
    },
    {
      key: 'total',
      title: 'Total',
      align: 'right',
      sortValue: (j) => j.totalMs,
      render: (j) => <Text variant="caption">{formatMs(j.totalMs)}</Text>,
    },
    {
      key: 'started',
      title: 'Started',
      sortValue: (j) => j.startedAt,
      render: (j) => <Text variant="caption">{j.startedAt.slice(0, 19).replace('T', ' ')}</Text>,
    },
    {
      key: 'error',
      title: 'Error',
      flex: 3,
      render: (j) => (
        <Text variant="caption" numberOfLines={2} muted>
          {j.error ?? ''}
        </Text>
      ),
    },
  ];

  return (
    <View style={styles.section}>
      <View style={styles.titleRow}>
        <Text variant="title">Recent attempts</Text>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="About attempt numbers"
          onPress={() => setHint((h) => !h)}
          testID="attempt-hint-toggle"
        >
          <Text variant="caption" style={{ color: colors.accent }}>
            ⓘ attempt numbers
          </Text>
        </Pressable>
      </View>
      {hint ? (
        <Text variant="caption" muted testID="attempt-hint">
          {ATTEMPT_HINT}
        </Text>
      ) : null}
      <View style={styles.legend} accessibilityRole="list">
        {BAR_STAGES.map((stage) => (
          <View key={stage} style={styles.legendItem}>
            <View style={[styles.swatch, { backgroundColor: stageColor(stage) }]} />
            <Text variant="caption">{STAGE_LABELS[stage]}</Text>
          </View>
        ))}
      </View>
      <DataTable
        testID="pipeline-jobs"
        columns={columns}
        rows={jobs}
        rowKey={(j) => j.id}
        status={jobs ? 'success' : status}
        onRetry={onRetry}
        emptyTitle="No transcode attempts yet"
        emptyDetail="Upload a clip from the app to see it here."
      />
    </View>
  );
}

const styles = StyleSheet.create({
  page: { gap: spacing.lg },
  header: { flexDirection: 'row', alignItems: 'baseline', gap: spacing.md },
  tiles: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md },
  banner: { borderWidth: 1, borderRadius: radius.md, padding: spacing.md },
  section: { gap: spacing.sm },
  titleRow: { flexDirection: 'row', alignItems: 'baseline', gap: spacing.md },
  actions: { flexDirection: 'row', gap: spacing.xs },
  charts: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md },
  chart: { flexGrow: 1, flexBasis: 420 },
  stageRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.md,
    gap: spacing.lg,
  },
  stageCell: { minWidth: 120, gap: 2 },
  bar: { flexDirection: 'row', height: 12, borderRadius: radius.sm, overflow: 'hidden' },
  legend: { flexDirection: 'row', gap: spacing.md, flexWrap: 'wrap' },
  legendItem: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
  swatch: { width: 10, height: 10, borderRadius: 2 },
});
