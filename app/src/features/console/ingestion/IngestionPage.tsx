import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import {
  adminGetIngestionRun,
  adminListIngestionRuns,
  adminListIngestionSources,
  adminQueueIngestionRun,
  getAdminGetIngestionRunQueryKey,
  getAdminListIngestionRunsQueryKey,
  getAdminListIngestionSourcesQueryKey,
} from '@/api/generated/admin-ingestion/admin-ingestion';
import {
  AdminListIngestionRunsStatus,
  type IngestionRun,
  type IngestionSource,
} from '@/api/generated/model';
import { ApiError } from '@/api/http';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { DataTable, type Column } from '../DataTable';
import {
  groupIssues,
  isActive,
  isBreakage,
  issueCountList,
  RUN_POLL_MS,
  WAITING_NOTE_AFTER_MS,
} from './issueKinds';

const STATUSES = Object.values(AdminListIngestionRunsStatus);
/** Issues listed per kind when a row is expanded; the rest are counted. */
const ISSUES_PER_KIND = 5;

/**
 * The console's Ingestion page: each gig source with "Run now", and the run history with its
 * issues. "Run now" only queues a run; a scheduler process claims it within seconds, and the
 * page polls the run until it ends. A VENUE run that met an empty or unreadable site is highlighted.
 */
export default function IngestionPage() {
  const queryClient = useQueryClient();
  const [source, setSource] = useState<string | undefined>(undefined);
  const [status, setStatus] = useState<AdminListIngestionRunsStatus | undefined>(undefined);
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set());

  const sources = useQuery({
    queryKey: getAdminListIngestionSourcesQueryKey(),
    queryFn: ({ signal }) => adminListIngestionSources({ signal }),
    // While any source is busy, its status (and the table) follow it.
    refetchInterval: (q) =>
      (q.state.data ?? []).some((s) => isActive(s.lastRun?.status)) ? RUN_POLL_MS : false,
  });
  const anyActive = (sources.data ?? []).some((s) => isActive(s.lastRun?.status));

  const params = { ...(source ? { source } : {}), ...(status ? { status } : {}) };
  const runs = useInfiniteQuery({
    queryKey: [...getAdminListIngestionRunsQueryKey(params), 'infinite'],
    queryFn: ({ pageParam, signal }) =>
      adminListIngestionRuns(
        { ...params, ...(pageParam ? { cursor: pageParam } : {}) },
        { signal },
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    refetchInterval: anyActive ? RUN_POLL_MS : false,
  });
  const rows = runs.data?.pages.flatMap((p) => p.items);

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: getAdminListIngestionSourcesQueryKey() });
    void queryClient.invalidateQueries({ queryKey: getAdminListIngestionRunsQueryKey() });
  };
  const toggle = (id: string) =>
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const columns = runColumns(expanded, toggle);

  return (
    <View style={styles.page}>
      <Text variant="display">Ingestion</Text>
      <Text muted>
        Where gigs come from. &quot;Run now&quot; queues a run; the scheduler process picks it up
        within about 15 seconds.
      </Text>
      <SourcePanel sources={sources} onRunEnded={refresh} />
      <View style={styles.filters}>
        <Chips
          label="Source"
          options={(sources.data ?? []).map((s) => s.name)}
          value={source}
          onChange={setSource}
          testID="ingestion-filter-source"
        />
        <Chips
          label="Status"
          options={STATUSES}
          value={status}
          onChange={setStatus}
          testID="ingestion-filter-status"
        />
      </View>
      <DataTable
        testID="ingestion-runs"
        columns={columns}
        rows={rows}
        rowKey={(r) => r.id}
        status={runs.status}
        onRetry={() => void runs.refetch()}
        emptyTitle={source || status ? 'No runs match these filters' : 'No ingestion runs yet'}
        emptyDetail={
          source || status
            ? undefined
            : 'Runs appear here after the 05:10 schedule, `make ingest`, or "Run now".'
        }
        hasMore={runs.hasNextPage}
        loadingMore={runs.isFetchingNextPage}
        onLoadMore={() => void runs.fetchNextPage()}
      />
    </View>
  );
}

function SourcePanel({
  sources,
  onRunEnded,
}: {
  sources: {
    status: 'pending' | 'error' | 'success';
    data?: IngestionSource[];
    refetch: () => unknown;
  };
  onRunEnded: () => void;
}) {
  if (sources.status === 'pending') {
    return (
      <View style={styles.cards} testID="ingestion-sources-loading">
        {[0, 1, 2].map((i) => (
          <View key={i} style={styles.cardSlot}>
            <Skeleton height={120} />
          </View>
        ))}
      </View>
    );
  }
  if (sources.status === 'error' || !sources.data) {
    return (
      <StateMessage
        testID="ingestion-sources-error"
        title="Couldn't load the gig sources"
        detail="The server didn't answer."
        onRetry={() => void sources.refetch()}
      />
    );
  }
  return (
    <View style={styles.cards}>
      {sources.data.map((s) => (
        <SourceCard key={s.name} source={s} onRunEnded={onRunEnded} />
      ))}
    </View>
  );
}

/** Seconds since `since` (ms), ticking once a second while `active`. */
function useElapsedMs(since: number | null, active: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return undefined;
    const t = setInterval(() => setNow(Date.now()), 1_000);
    return () => clearInterval(t);
  }, [active]);
  return since === null ? 0 : Math.max(0, now - since);
}

function queueErrorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.code === 'INGESTION_ALREADY_RUNNING')
      return 'This source already has a queued or running run.';
    if (e.code === 'SOURCE_NOT_ENABLED') return "This deployment doesn't run this source.";
    return e.message;
  }
  return "Couldn't queue the run. Try again.";
}

export function SourceCard({
  source,
  onRunEnded,
}: {
  source: IngestionSource;
  onRunEnded: () => void;
}) {
  const { colors } = useTheme();
  const [tracked, setTracked] = useState<{ runId: string; pressedAt: number } | null>(null);
  const queue = useMutation({
    mutationFn: () => adminQueueIngestionRun({ source: source.name }),
    onSuccess: (r) => setTracked({ runId: r.runId, pressedAt: Date.now() }),
  });
  const run = useQuery({
    queryKey: getAdminGetIngestionRunQueryKey(tracked?.runId ?? ''),
    queryFn: ({ signal }) => adminGetIngestionRun(tracked?.runId ?? '', { signal }),
    enabled: tracked !== null,
    refetchInterval: (q) => (isActive(q.state.data?.run.status ?? 'QUEUED') ? RUN_POLL_MS : false),
  });
  const trackedStatus = tracked ? (run.data?.run.status ?? 'QUEUED') : undefined;
  const trackedEnded = tracked !== null && run.data !== undefined && !isActive(trackedStatus);
  useEffect(() => {
    if (trackedEnded) onRunEnded();
    // Once per run: when the tracked run's status turns final.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [trackedEnded, tracked?.runId]);

  const waiting = trackedStatus === 'QUEUED';
  const elapsed = useElapsedMs(tracked?.pressedAt ?? null, waiting);
  // The sources list can lag behind the tracked run by one poll: once that run has ended, its stale
  // "RUNNING" there doesn't keep the button disabled.
  const lastIsTracked = trackedEnded && source.lastRun?.id === tracked?.runId;
  const busy =
    queue.isPending ||
    (tracked !== null && isActive(trackedStatus)) ||
    (!lastIsTracked && isActive(source.lastRun?.status));
  const last =
    trackedEnded && run.data && (lastIsTracked || !source.lastRun) ? run.data.run : source.lastRun;

  return (
    <View
      testID={`ingestion-source-${source.name}`}
      style={[styles.card, { borderColor: colors.border, backgroundColor: colors.surface }]}
    >
      <View style={styles.cardHead}>
        <Text variant="title">{source.name}</Text>
        <View
          style={[
            styles.tag,
            { backgroundColor: source.enabled ? colors.accent2Soft : colors.surfaceRaised },
          ]}
        >
          <Text variant="label">{source.enabled ? source.mode.toUpperCase() : 'OFF'}</Text>
        </View>
      </View>
      {last ? (
        <Text variant="caption" muted testID={`ingestion-last-${source.name}`}>
          {`Last run ${last.status} · ${formatTime(last.startedAt)}`}
        </Text>
      ) : (
        <Text variant="caption" muted>
          Never run
        </Text>
      )}
      {tracked && isActive(trackedStatus) ? (
        <Text variant="caption" testID={`ingestion-tracked-${source.name}`}>
          {trackedStatus === 'QUEUED' ? 'Queued…' : 'Running…'}
        </Text>
      ) : null}
      {trackedEnded && run.data ? (
        <Text variant="caption" testID={`ingestion-tracked-${source.name}`}>
          {`Finished ${run.data.run.status}: ${run.data.run.fetched} fetched, ${run.data.run.created} created, ${run.data.run.updated} updated`}
        </Text>
      ) : null}
      {waiting && elapsed >= WAITING_NOTE_AFTER_MS ? (
        <Text
          variant="caption"
          style={{ color: colors.warning }}
          testID={`ingestion-waiting-${source.name}`}
        >
          Waiting for the scheduler process. Locally `make api` runs one (roles api,scheduler).
        </Text>
      ) : null}
      {queue.isError ? (
        <Text variant="caption" style={{ color: colors.danger }}>
          {queueErrorMessage(queue.error)}
        </Text>
      ) : null}
      <View style={styles.cardFoot}>
        <Button
          title={queue.isPending ? 'Queueing…' : busy ? 'In progress' : 'Run now'}
          size="small"
          disabled={!source.enabled || busy}
          onPress={() => queue.mutate()}
          testID={`ingestion-run-now-${source.name}`}
        />
        {source.enabled ? null : (
          <Text variant="caption" muted>
            Not enabled in this deployment
          </Text>
        )}
      </View>
    </View>
  );
}

function runColumns(expanded: ReadonlySet<string>, toggle: (id: string) => void) {
  const columns: Column<IngestionRun>[] = [
    {
      key: 'source',
      title: 'Source',
      flex: 1.4,
      sortValue: (r) => r.source,
      render: (r) => <SourceCell run={r} />,
    },
    {
      key: 'status',
      title: 'Status',
      sortValue: (r) => r.status,
      render: (r) => <StatusText status={r.status} />,
    },
    {
      key: 'started',
      title: 'Started',
      flex: 1.3,
      sortValue: (r) => r.startedAt,
      render: (r) => (
        <View>
          <Text variant="caption">{formatTime(r.startedAt)}</Text>
          <Text variant="caption" muted>
            {`${r.trigger.toLowerCase()} · ${formatDuration(r.startedAt, r.finishedAt)}`}
          </Text>
        </View>
      ),
    },
    {
      key: 'fetched',
      title: 'Fetched',
      align: 'right',
      sortValue: (r) => r.fetched,
      render: (r) => <Text>{r.fetched}</Text>,
    },
    {
      key: 'changes',
      title: 'New / upd / skip',
      flex: 1.2,
      align: 'right',
      sortValue: (r) => r.created,
      render: (r) => <Text variant="caption">{`${r.created} / ${r.updated} / ${r.skipped}`}</Text>,
    },
    {
      key: 'issues',
      title: 'Issues',
      flex: 4,
      sortValue: (r) => issueTotal(r),
      render: (r) => <IssuesCell run={r} open={expanded.has(r.id)} onToggle={() => toggle(r.id)} />,
    },
  ];
  return columns;
}

function issueTotal(run: IngestionRun): number {
  return Object.values(run.issueCounts).reduce((a, b) => a + b, 0);
}

function SourceCell({ run }: { run: IngestionRun }) {
  const { colors } = useTheme();
  const broken = isBreakage(run);
  return (
    <View style={styles.sourceCell}>
      <View
        style={[styles.marker, { backgroundColor: broken ? colors.danger : 'transparent' }]}
        testID={broken ? 'run-breakage' : undefined}
      />
      <View style={styles.sourceText}>
        <Text style={broken ? { color: colors.danger, fontWeight: '600' } : null}>
          {run.source}
        </Text>
        {broken ? (
          <Text variant="caption" style={{ color: colors.danger }}>
            A venue site may have changed
          </Text>
        ) : null}
        {run.error ? (
          <Text variant="caption" muted numberOfLines={2}>
            {run.error}
          </Text>
        ) : null}
      </View>
    </View>
  );
}

function StatusText({ status }: { status: string }) {
  const { colors } = useTheme();
  const color =
    status === 'FAILED'
      ? colors.danger
      : status === 'PARTIAL'
        ? colors.warning
        : status === 'SUCCEEDED'
          ? colors.success
          : colors.accent;
  return <Text style={{ color, fontWeight: '600' }}>{status}</Text>;
}

function IssuesCell({
  run,
  open,
  onToggle,
}: {
  run: IngestionRun;
  open: boolean;
  onToggle: () => void;
}) {
  const { colors } = useTheme();
  const counts = issueCountList(run.issueCounts);
  if (counts.length === 0) {
    return (
      <Text variant="caption" muted>
        None
      </Text>
    );
  }
  return (
    <View style={styles.issuesCell}>
      <Pressable
        accessibilityRole="button"
        accessibilityState={{ expanded: open }}
        onPress={onToggle}
        testID={`ingestion-issues-toggle-${run.id}`}
      >
        <Text variant="caption">
          <Text variant="caption" style={{ color: colors.accent, fontWeight: '600' }}>
            {open ? '▾ ' : '▸ '}
          </Text>
          {counts
            .slice(0, 3)
            .map(([kind, n]) => `${kind} ×${n}`)
            .join(' · ')}
          {counts.length > 3 ? ` · +${counts.length - 3} more kinds` : ''}
        </Text>
      </Pressable>
      {open ? <RunIssues runId={run.id} /> : null}
    </View>
  );
}

/** A run's stored issues, grouped by kind, with what each kind means. */
export function RunIssues({ runId }: { runId: string }) {
  const { colors } = useTheme();
  const detail = useQuery({
    queryKey: getAdminGetIngestionRunQueryKey(runId),
    queryFn: ({ signal }) => adminGetIngestionRun(runId, { signal }),
  });
  if (detail.status === 'pending') return <Skeleton height={48} />;
  if (detail.status === 'error') {
    return (
      <StateMessage title="Couldn't load this run's issues" onRetry={() => void detail.refetch()} />
    );
  }
  const groups = groupIssues(detail.data.issues);
  return (
    <View style={styles.groups} testID={`ingestion-issues-${runId}`}>
      {groups.map((g) => (
        <View
          key={g.kind}
          testID={`issue-group-${g.kind}`}
          style={[styles.group, { borderLeftColor: colors.border }]}
        >
          <Text variant="label">{`${g.kind} · ${g.issues.length} stored`}</Text>
          <Text variant="caption" muted testID={`issue-explanation-${g.kind}`}>
            {g.explanation ?? 'No explanation for this kind yet: shown as the backend named it.'}
          </Text>
          {g.issues.slice(0, ISSUES_PER_KIND).map((issue, i) => (
            <Text key={i} variant="caption" numberOfLines={2}>
              {`${issue.externalId ?? '—'}  ${issue.detail ?? ''}`}
            </Text>
          ))}
          {g.issues.length > ISSUES_PER_KIND ? (
            <Text variant="caption" muted>
              {`+${g.issues.length - ISSUES_PER_KIND} more`}
            </Text>
          ) : null}
        </View>
      ))}
    </View>
  );
}

function Chips<T extends string>({
  label,
  options,
  value,
  onChange,
  testID,
}: {
  label: string;
  options: readonly T[];
  value: T | undefined;
  onChange: (v: T | undefined) => void;
  testID: string;
}) {
  const { colors } = useTheme();
  const all = [undefined, ...options];
  return (
    <View style={styles.chips} role="radiogroup" accessibilityLabel={label}>
      <Text variant="label" muted>
        {label.toUpperCase()}
      </Text>
      {all.map((o) => {
        const selected = value === o;
        return (
          <Pressable
            key={o ?? 'all'}
            role="radio"
            aria-checked={selected}
            onPress={() => onChange(o)}
            testID={`${testID}-${o ?? 'all'}`}
            style={[
              styles.chip,
              { borderColor: colors.border },
              selected ? { backgroundColor: colors.accentSoft, borderColor: colors.accent } : null,
            ]}
          >
            <Text variant="caption">{o ?? 'All'}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

function formatTime(iso: string): string {
  return iso.slice(0, 16).replace('T', ' ') + ' UTC';
}

function formatDuration(startedAt: string, finishedAt: string | null | undefined): string {
  if (!finishedAt) return 'in progress';
  const seconds = Math.max(0, Math.round((Date.parse(finishedAt) - Date.parse(startedAt)) / 1000));
  return seconds < 60 ? `${seconds} s` : `${Math.floor(seconds / 60)} min ${seconds % 60} s`;
}

const styles = StyleSheet.create({
  page: { gap: spacing.md },
  cards: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md },
  cardSlot: { flexBasis: 280, flexGrow: 1 },
  card: {
    flexBasis: 280,
    flexGrow: 1,
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.md,
    gap: spacing.xs,
  },
  cardHead: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  cardFoot: { flexDirection: 'row', alignItems: 'center', gap: spacing.md, marginTop: spacing.sm },
  tag: { borderRadius: radius.sm, paddingHorizontal: spacing.sm, paddingVertical: 2 },
  filters: { gap: spacing.sm },
  chips: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs, flexWrap: 'wrap' },
  chip: {
    borderWidth: 1,
    borderRadius: radius.pill,
    paddingHorizontal: spacing.md,
    paddingVertical: 4,
  },
  sourceCell: { flexDirection: 'row', gap: spacing.sm, alignItems: 'stretch' },
  marker: { width: 4, borderRadius: 2 },
  sourceText: { flexShrink: 1 },
  issuesCell: { gap: spacing.sm },
  groups: { gap: spacing.sm },
  group: { borderLeftWidth: 2, paddingLeft: spacing.sm, gap: 2 },
});
